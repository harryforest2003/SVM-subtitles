package io.github.harryforest2003.svmsubtitles.transcribe;

/** A speech-to-text engine. Implementations are only called from the transcription worker threads. */
public interface Transcriber extends AutoCloseable {
	/**
	 * @param samples mono 16 kHz audio in [-1, 1]
	 */
	Transcript transcribe(float[] samples, SpeechRequest request) throws Exception;

	String describe();

	/** True when the work happens on this machine (and so competes with the game for CPU). */
	boolean isLocal();

	@Override
	void close();
}
