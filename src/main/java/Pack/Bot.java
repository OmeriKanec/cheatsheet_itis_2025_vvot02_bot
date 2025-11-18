package Pack;

import com.google.gson.*;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.function.Function;

public class Bot implements Function<String , Response> {

    private static final String TELEGRAM_BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");

    @Override
    public Response apply(String eventJson) {
        JsonObject envelope = JsonParser.parseString(eventJson).getAsJsonObject();
        String bodyString = envelope.get("body").getAsString();
        JsonObject update = JsonParser.parseString(bodyString).getAsJsonObject();
        handleUpdate(update);
        return new Response(200, null);
    }

    public void handleUpdate(JsonObject update) {
        if (!update.has("message")) return;
        JsonObject message = update.getAsJsonObject("message");
        System.out.println("Message: " + message);
        if (!message.has("text")) return;
        String text = message.get("text").getAsString();
        System.out.println("text: " + text);
        long id = message.getAsJsonObject("chat").get("id").getAsLong();
        System.out.println("id: " + id);
        if (text.equals("/start") || text.equals("/help")) {
                sendText(id, "Я помогу ответить на экзаменационный вопрос по «Операционным системам».\n" +
                        "Присылайте вопрос — фото или текстом.");
        }
    }

    public static void sendText(Long chatId, String text) {
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

}
