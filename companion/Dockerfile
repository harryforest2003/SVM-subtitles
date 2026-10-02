# Speech server for SVM Subtitles. Works on a free Hugging Face Docker Space (see README.md) or any Docker host.
FROM python:3.12-slim

RUN useradd -m -u 1000 user
USER user
ENV HOME=/home/user \
    PATH=/home/user/.local/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    HF_HUB_DISABLE_TELEMETRY=1 \
    MODEL=small \
    LIVE_MODEL=base \
    TTS_VOICE=en_US-lessac-medium
WORKDIR /home/user/app

COPY --chown=user requirements.txt .
RUN pip install --no-cache-dir --user -r requirements.txt

# Download the models while building, so the server starts fast and never fetches anything at runtime.
RUN python -c "from faster_whisper import WhisperModel; [WhisperModel(m, device='cpu', compute_type='int8') for m in ('$MODEL', '$LIVE_MODEL')]" \
 && python -m piper.download_voices --download-dir voices "$TTS_VOICE"

COPY --chown=user whisper_server.py .

EXPOSE 7860
# SVM_API_KEY must be set (a Space secret); without it the server refuses to start.
CMD python whisper_server.py --host 0.0.0.0 --port 7860 --model "$MODEL" --live-model "$LIVE_MODEL" \
    --tts-voice "$TTS_VOICE" --voices-dir voices
