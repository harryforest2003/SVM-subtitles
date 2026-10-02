package io.github.harryforest2003.svmsubtitles.chat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PrivateMessageCommandTest {
	@Test
	void autoPrefersMsg() {
		assertEquals("/msg Steve ", PrivateMessageCommand.build("auto", "Steve", dispatcherWith("tell", "msg", "w")));
	}

	@Test
	void autoFallsBackToWhateverTheServerHas() {
		assertEquals("/w Alex ", PrivateMessageCommand.build("auto", "Alex", dispatcherWith("w", "spawn")));
		assertEquals("/pm Alex ", PrivateMessageCommand.build("AUTO", "Alex", dispatcherWith("pm")));
		assertEquals("/msg Alex ", PrivateMessageCommand.build("auto", "Alex", dispatcherWith("spawn")));
		assertEquals("/msg Alex ", PrivateMessageCommand.build("auto", "Alex", null));
	}

	@Test
	void customCommands() {
		assertEquals("/tell Steve ", PrivateMessageCommand.build("/tell {player} ", "Steve", null));
		assertEquals("/m Steve ", PrivateMessageCommand.build("m", "Steve", null));
		assertEquals("/dm Steve ", PrivateMessageCommand.build("/dm", "Steve", null));
	}

	@Test
	void canBeTurnedOff() {
		assertNull(PrivateMessageCommand.build("none", "Steve", null));
		assertNull(PrivateMessageCommand.build("", "Steve", null));
	}

	private static CommandDispatcher<Object> dispatcherWith(String... commands) {
		CommandDispatcher<Object> dispatcher = new CommandDispatcher<>();
		for (String command : commands) {
			dispatcher.register(LiteralArgumentBuilder.literal(command).executes(ctx -> 1));
		}
		return dispatcher;
	}
}
