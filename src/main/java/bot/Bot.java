package bot;

import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Bot extends TelegramWebhookBot {

    private static final String TELEGRAM_BOT_TOKEN = System.getenv("TELEGRAM_BOT_TOKEN");
    private static final String BOT_PATH = "webhook";

    @Override
    public String getBotUsername() {
        return "Шпаргалка ИТИС 2025 vvot02";
    }

    @Override
    public String getBotToken() {
        return TELEGRAM_BOT_TOKEN;
    }

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

    @Override
    public BotApiMethod<?> onWebhookUpdateReceived(Update update) {
        var message = update.getMessage();
        var user = message.getFrom();
        var id = user.getId();
        if (message.isCommand()) {
            if (message.getText().equals("/start") || message.getText().equals("/help")) {
                sendText(id, "Я помогу ответить на экзаменационный вопрос по «Операционным системам».\n" +
                        "Присылайте вопрос — фото или текстом.");
            }
        }
        return null;
    }

    @Override
    public String getBotPath() {
        return BOT_PATH;
    }
}
