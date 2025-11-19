package Pack;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.function.Function;

public class Bot implements Function<String , Response> {

    private static final String TELEGRAM_BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String BUCKET_NAME = System.getenv("BUCKET_NAME");
    private static final String OBJECT_KEY = System.getenv("OBJECT_KEY");
    private static final String YANDEX_API_KEY = System.getenv("YANDEX_API_KEY");
    private static final String FOLDER_ID = System.getenv("FOLDER_ID");

    @Override
    public Response apply(String eventJson) {
        JsonObject envelope = JsonParser.parseString(eventJson).getAsJsonObject();
        String bodyString = envelope.get("body").getAsString();
        JsonObject update = JsonParser.parseString(bodyString).getAsJsonObject();
        handleUpdate(update);
        return new Response(200, null);
    }

    //Переменная для хранения последнего media_group_id
    private String media_group_id;

    public void handleUpdate(JsonObject update) {
        if (!update.has("message")) return;
        JsonObject message = update.getAsJsonObject("message");
        long id = message.getAsJsonObject("chat").get("id").getAsLong();
        //Обработка текстового сообщения
        if (message.has("text")) {
            String text = message.get("text").getAsString();
            if (text.equals("/start") || text.equals("/help")) {
                sendText(id, "Я помогу ответить на экзаменационный вопрос по «Операционным системам».\n" +
                        "Присылайте вопрос — фото или текстом.");
            } else {
                processTextWithGpt(text, id);
            }
        }
        //Обработка фото
        else if (message.has("photo")) {
            JsonArray photos = message.getAsJsonArray("photo");
            //Если альбом, то должны ответить, что можем обработать только одно фото
            if (message.has("media_group_id")) {
                //Если уже был ответ на этот альбом, то больше не отвечаем
                if (String.valueOf(message.get("media_group_id")).equals(media_group_id)){
                    return;
                }
                sendText(id, "Я могу обработать только одну фотографию.");
                media_group_id = String.valueOf(message.get("media_group_id"));
                return;
            }
            String fileId = photos.get(photos.size() - 1).getAsJsonObject().get("file_id").getAsString();
            String filePath = getFilePath(fileId);
            String recognizedText = recognizeTextYandexOcr(downloadTelegramPhoto(filePath), filePath);
            if (recognizedText == null || recognizedText.isBlank()) {
                sendText(id, "Я не могу обработать эту фотографию.");
            } else {
                processTextWithGpt(recognizedText, id);
            }
        //Всё остальное не поддерживаем
        } else {
            sendText(id, "Я могу обработать только текстовое сообщение или фотографию.");
        }
    }

    public void processTextWithGpt(String text, long id) {
        String answer = getGptAnswer(text, getInstructionFromPublicObjectStorage());
        if (!(answer == null)) {
            sendText(id, answer);
        } else {
            sendText(id, "Я не смог подготовить ответ на экзаменационный вопрос.");
        }
    }

    //Отправка сообщения пользователю через http запрос
    public void sendText(Long chatId, String text) {
        String url = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/sendMessage";

        String json = String.format(
                "{\"chat_id\": \"%s\", \"text\": \"%s\"}",
                chatId, text.replace("\"", "\\\"")
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    //Достаём инструкцию из бакета(так как она публичная для read, то просто http запросом)
    private String getInstructionFromPublicObjectStorage() {
        String url = "https://storage.yandexcloud.net/" + BUCKET_NAME + "/" + OBJECT_KEY;
        try {
            HttpClient client = java.net.http.HttpClient.newHttpClient();
            HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            System.err.println("Ошибка скачивания инструкции: " + e.getMessage());
            return "";
        }
    }

    //Запрос к YandexGPT
    private String getGptAnswer(String question, String instruction) {
        String apiUrl = "https://llm.api.cloud.yandex.net/foundationModels/v1/completion";
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty("modelUri", "gpt://" + FOLDER_ID + "/yandexgpt-lite");
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("text", instruction);
        messages.add(sys);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("text", question);
        messages.add(user);
        reqJson.add("messages", messages);
        reqJson.addProperty("temperature", 0.3);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + YANDEX_API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(reqJson.toString()))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject result = respJson.getAsJsonObject("result");
            if (result != null) {
                JsonArray alternatives = result.getAsJsonArray("alternatives");
                if (alternatives != null && !alternatives.isEmpty()) {
                    return alternatives.get(0).getAsJsonObject().getAsJsonObject("message").get("text").getAsString();
                }
            }
        } catch(Exception e){
            System.err.println("Ошибка GPT-запроса: " + e.getMessage());
        }
        return null;
    }

    //Получем ссылку на скачивание фото по его id
    public String getFilePath(String fileId) {
        String getFileUrl = "https://api.telegram.org/bot" + TELEGRAM_BOT_TOKEN + "/getFile?file_id=" + fileId;
        HttpRequest getFileRequest = HttpRequest.newBuilder()
                .uri(URI.create(getFileUrl))
                .GET()
                .build();
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> getFileResponse;
        try {
            getFileResponse = client.send(getFileRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        JsonObject js = JsonParser.parseString(getFileResponse.body()).getAsJsonObject();
        return js.getAsJsonObject("result").get("file_path").getAsString();
    }

    //Скачиваем фото по ссылке в байт массив
    public byte[] downloadTelegramPhoto(String filePath) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            String downloadUrl = "https://api.telegram.org/file/bot" + TELEGRAM_BOT_TOKEN + "/" + filePath;
            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .GET()
                    .build();
            HttpResponse<byte[]> downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());

            return downloadResponse.body();
        } catch (Exception e) {
            System.err.println("Ошибка скачивания фото: " + e.getMessage());
            return null;
        }
    }

    //Отправляем байт массив полученный ранее на распознавание к ocr(конечно же после его перевода в Base64),
    // так же динамически из filepath получаем расширение фотографии
    public String recognizeTextYandexOcr(byte[] imageBytes, String filePath) {
        try {
            int dotIdx = filePath.lastIndexOf('.');
            String extension = filePath.substring(dotIdx + 1).toLowerCase();
            String ocrMime = switch (extension) {
                case "jpg", "jpeg" -> "JPEG";
                case "png"         -> "PNG";
                default -> throw new IllegalArgumentException("Неподдерживаемое расширение: " + extension);
            };

            String imageBase64 = Base64.getEncoder().encodeToString(imageBytes);
            String jsonBody = String.format(
                    "{\"mimeType\": \"%s\", \"languageCodes\": [\"*\"], " +
                            "\"model\": \"page\", \"content\": \"%s\"}", ocrMime, imageBase64);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://ocr.api.cloud.yandex.net/ocr/v1/recognizeText"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + YANDEX_API_KEY)
                    .header("x-folder-id", FOLDER_ID)
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject respJson = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonObject result = respJson.getAsJsonObject("result");
            JsonObject textAnnotation = result.getAsJsonObject("textAnnotation");
            return textAnnotation.get("fullText").getAsString();
        } catch (Exception e) {
            System.err.println("Ошибка OCR-запроса: " + e.getMessage());
            return null;
        }
    }

}
