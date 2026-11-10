import Foundation

@main enum ProtocolChecks {
    static func main() {
        // Server payloads used by Android 0.16.2 and iOS must preserve IDs and bot/card metadata.
        let bot = Msg(["id": 42, "chat_id": 7, "sender_id": 0, "text": "Готово", "created": 100,
                       "bot": ["request_id": 11, "requested_by": 3, "target_chat_id": 8, "error": true],
                       "card": ["type": "poll", "id": 9, "title": "Когда?"]])
        precondition(bot.senderId == 0 && bot.bot?["request_id"] as? Int == 11)
        precondition(bot.cardType == "poll" && bot.card?["id"] as? Int == 9)
        let chat = Chat(["id": 7, "kind": "bot", "title": "JT бот", "members": []])
        precondition(chat.kind == "bot" && !chat.isGroup)
        let photo = Msg(["id": 43, "chat_id": 7, "sender_id": 3, "photo": "image.jpg", "photo_w": 1080, "photo_h": 1920,
                         "reply": ["id": 42, "sender_id": 0, "text": "Готово", "deleted": false]])
        precondition(photo.photo == "image.jpg" && photo.photoH == 1920 && photo.quote?.id == 42)
        let voice = Msg(["id": 44, "chat_id": 7, "media_kind": "voice", "media": "voice.m4a", "duration": 1500, "wave": "12345"])
        precondition(voice.mediaKind == "voice" && voice.duration == 1500 && voice.wave == "12345")
        let url = Api.url("/users/search", ["q": "тест & имя"])
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first?.value
        precondition(query == "тест & имя")
        print("Protocol checks passed (bot, cards, photo replies, voice, URL encoding)")
    }
}
