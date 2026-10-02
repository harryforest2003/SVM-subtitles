package io.github.harryforest2003.svmsubtitles.network;

import io.github.harryforest2003.svmsubtitles.SvmSubtitles;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Tells clients with the mod whether the server is transcribing, so they don't do it a second time. */
public record SubtitlesStatusPayload(boolean serverTranscribing) implements CustomPacketPayload {
	public static final Type<SubtitlesStatusPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SvmSubtitles.MOD_ID, "status"));
	public static final StreamCodec<ByteBuf, SubtitlesStatusPayload> CODEC =
			ByteBufCodecs.BOOL.map(SubtitlesStatusPayload::new, SubtitlesStatusPayload::serverTranscribing);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
