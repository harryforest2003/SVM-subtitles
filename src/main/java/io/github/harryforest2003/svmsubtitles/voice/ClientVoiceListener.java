package io.github.harryforest2003.svmsubtitles.voice;

import java.util.UUID;

/**
 * Receives decoded voice audio on the client. Lives in the common source set so the voice chat plugin can
 * hand audio to the client code without loading client classes on a dedicated server.
 */
public interface ClientVoiceListener {
	/** Audio from another speaker, 48 kHz mono. Called on Simple Voice Chat's audio threads. */
	void onReceive(UUID speaker, short[] audio, boolean whispering);

	/** This player's own microphone audio as it is sent. */
	void onOwnVoice(short[] audio, boolean whispering);
}
