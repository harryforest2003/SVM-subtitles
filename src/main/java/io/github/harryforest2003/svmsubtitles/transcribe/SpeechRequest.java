package io.github.harryforest2003.svmsubtitles.transcribe;

/**
 * @param language  ISO language code, or "auto"
 * @param translate turn other languages into English
 * @param prompt    names and words that are likely to come up (may be empty)
 */
public record SpeechRequest(String language, boolean translate, String prompt) {
}
