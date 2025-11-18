package bot;

import io.github.cdimascio.dotenv.Dotenv;
import org.telegram.telegrambots.bots.TelegramWebhookBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

public class Bot extends TelegramWebhookBot {
    @Override
    public String getBotUsername() {
        return "Шпаргалка ИТИС 2025 vvot02";
    }

    @Override
    public String getBotToken() {
        return Dotenv.load().get("TOKEN");
    }

    public void sendText(Long who, String what){
        SendMessage sm = SendMessage.builder()
                .chatId(who.toString())
                .text(what).build();
        try {
            execute(sm);
        } catch (TelegramApiException e) {
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
        return null;
    }
}
