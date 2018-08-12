package me.paulf.wings.client;

import me.paulf.wings.Proxy;
import me.paulf.wings.client.renderer.LayerWings;
import me.paulf.wings.server.capability.Flight;
import me.paulf.wings.server.item.WingsItems;
import me.paulf.wings.server.net.serverbound.MessageControlFlying;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.color.ItemColors;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.player.EntityPlayer;

public final class ClientProxy extends Proxy {
	@Override
	protected void init() {
		super.init();
		Minecraft mc = Minecraft.getMinecraft();
		ItemColors colors = mc.getItemColors();
		colors.registerItemColorHandler((stack, pass) -> pass == 0 ? 0x9B172D : 0xFFFFFF, WingsItems.BAT_BLOOD);
		for (RenderPlayer renderer : mc.getRenderManager().getSkinMap().values()) {
			renderer.addLayer(new LayerWings(renderer));
		}
	}

	@Override
	public void addFlightListeners(EntityPlayer player, Flight flight) {
		if (player.isUser()) {
			Flight.Notifier notifier = Flight.Notifier.of(
				() -> {},
				p -> {},
				() -> network.sendToServer(new MessageControlFlying(flight.isFlying()))
			);
			flight.registerSyncListener(players -> players.notify(notifier));
		}
	}
}
