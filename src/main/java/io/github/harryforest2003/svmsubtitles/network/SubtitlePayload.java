package io.github.harryforest2003.svmsubtitles.network;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * A subtitle for clients that have the mod, instead of a plain chat line, so they can highlight their name,
 * play an alert and show live captions on screen. Players without the mod get normal chat messages.
 *
 * @param pmCommand what clicking the name suggests, or "" for none
 * @param language  spoken language when the text was translated, or ""
 */
public record SubtitlePayload(UUID speaker, String name, String text, String format, String pmCommand, String language, int kind)
		implements CustomPacketPayload {
	public static final int FINAL = 0;
	public static final int PARTIAL = 1;
	public static final int TTS = 2;

	public static final Type<SubtitlePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SvmSubtitles.MOD_ID, "subtitle"));
	public static final StreamCodec<ByteBuf, SubtitlePayload> CODEC = StreamCodec.composite(
			UUIDUtil.STREAM_CODEC, SubtitlePayload::speaker,
			ByteBufCodecs.stringUtf8(64), SubtitlePayload::name,
			ByteBufCodecs.stringUtf8(1024), SubtitlePayload::text,
			ByteBufCodecs.stringUtf8(256), SubtitlePayload::format,
			ByteBufCodecs.stringUtf8(256), SubtitlePayload::pmCommand,
			ByteBufCodecs.stringUtf8(16), SubtitlePayload::language,
			ByteBufCodecs.VAR_INT, SubtitlePayload::kind,
			SubtitlePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
