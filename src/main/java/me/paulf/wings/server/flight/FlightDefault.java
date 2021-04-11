package me.paulf.wings.server.flight;

import com.google.common.collect.Lists;
import me.paulf.wings.server.apparatus.FlightApparatus;
import me.paulf.wings.server.apparatus.FlightApparatuses;
import me.paulf.wings.util.CubicBezier;
import me.paulf.wings.util.Mth;
import me.paulf.wings.util.NBTSerializer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.Util;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;

import java.util.List;
import java.util.function.Supplier;

public final class FlightDefault implements Flight {
	private static final CubicBezier FLY_AMOUNT_CURVE = new CubicBezier(0.37F, 0.13F, 0.3F, 1.12F);

	private static final int INITIAL_TIME_FLYING = 0;

	private static final int MAX_TIME_FLYING = 20;

	private static final float MIN_SPEED = 0.3F;

	private static final float MAX_SPEED = 0.715F;

	private static final float Y_BOOST = 0.5F;

	private static final float FALL_REDUCTION = 0.9F;

	private static final float PITCH_OFFSET = 30.0F;

	private final List<FlyingListener> flyingListeners = Lists.newArrayList();

	private final List<SyncListener> syncListeners = Lists.newArrayList();

	private final WingState voidState = new WingState(Items.AIR, FlightApparatus.FlightState.VOID);

	private int prevTimeFlying = INITIAL_TIME_FLYING;

	private int timeFlying = INITIAL_TIME_FLYING;

	private boolean isFlying;

	private WingState state = this.voidState;

	@Override
	public void setIsFlying(final boolean isFlying, final PlayerSet players) {
		if (this.isFlying != isFlying) {
			this.isFlying = isFlying;
			this.flyingListeners.forEach(FlyingListener.onChangeUsing(isFlying));
			this.sync(players);
		}
	}

	@Override
	public boolean isFlying() {
		return this.isFlying;
	}

	@Override
	public void setTimeFlying(final int timeFlying) {
		this.timeFlying = timeFlying;
	}

	@Override
	public int getTimeFlying() {
		return this.timeFlying;
	}

	@Override
	public float getFlyingAmount(final float delta) {
		return FLY_AMOUNT_CURVE.eval(Mth.lerp(this.getPrevTimeFlying(), this.getTimeFlying(), delta) / MAX_TIME_FLYING);
	}

	private void setPrevTimeFlying(final int prevTimeFlying) {
		this.prevTimeFlying = prevTimeFlying;
	}

	private int getPrevTimeFlying() {
		return this.prevTimeFlying;
	}

	@Override
	public void registerFlyingListener(final FlyingListener listener) {
		this.flyingListeners.add(listener);
	}

	@Override
	public void registerSyncListener(final SyncListener listener) {
		this.syncListeners.add(listener);
	}

	@Override
	public boolean canFly(final PlayerEntity player) {
		final ItemStack stack = FlightApparatuses.find(player);
		return FlightApparatuses.get(stack).filter(apparatus -> apparatus.isUsable(player, stack)).isPresent();
	}

	@Override
	public boolean canLand(final PlayerEntity player, final ItemStack wings) {
		return FlightApparatuses.get(wings).filter(apparatus -> apparatus.isLandable(player, wings)).isPresent();
	}

	private void onWornUpdate(final PlayerEntity player, final ItemStack wings) {
		if (player.isEffectiveAi()) {
			if (this.isFlying()) {
				final float speed = (float) MathHelper.clampedLerp(MIN_SPEED, MAX_SPEED, player.zza);
				final float elevationBoost = Mth.transform(
					Math.abs(player.xRot),
					45.0F, 90.0F,
					1.0F, 0.0F
				);
				final float pitch = -Mth.toRadians(player.xRot - PITCH_OFFSET * elevationBoost);
				final float yaw = -Mth.toRadians(player.yRot) - Mth.PI;
				final float vxz = -MathHelper.cos(pitch);
				final float vy = MathHelper.sin(pitch);
				final float vz = MathHelper.cos(yaw);
				final float vx = MathHelper.sin(yaw);
				player.setDeltaMovement(
					vx * vxz * speed,
					vy * (speed + Y_BOOST * (player.xRot > 0.0F ? elevationBoost : 1.0D)),
					vz * vxz * speed
				);
//				player.setDeltaMovement(0.000000000000000000000000000000001d, 0.000000000000000000000000000000001d, 0.000000000000000000000000000000001d);
			}
			if (this.canLand(player, wings)) {
				final Vector3d mot = player.position();
				if (mot.y() < 0.0D) {
					player.setDeltaMovement(mot.multiply(1.0D, FALL_REDUCTION, 1.0D));
				}
				player.fallDistance = 0.0F;
			}
		}
		if (!player.level.isClientSide) {
			Util.ifElse(FlightApparatuses.get(wings).resolve(), apparatus -> {
				if (apparatus.isUsable(player, wings)) {
					(this.state = this.state.next(wings, apparatus)).onUpdate(player, wings);
				} else if (this.isFlying()) {
					this.setIsFlying(false, PlayerSet.ofAll());
					this.state = this.state.next();
				}
			}, () -> {
				this.state = this.state.next();
			});
		}
	}

	@Override
	public void tick(final PlayerEntity player, final ItemStack wings) {
		if (!wings.isEmpty()) {
			this.onWornUpdate(player, wings);
		} else if (!player.level.isClientSide && this.isFlying()) {
			this.setIsFlying(false, Flight.PlayerSet.ofAll());
		}
		this.setPrevTimeFlying(this.getTimeFlying());
		if (this.isFlying()) {
			if (this.getTimeFlying() < MAX_TIME_FLYING) {
				this.setTimeFlying(this.getTimeFlying() + 1);
			} else if (player.isLocalPlayer() && player.isOnGround()) {
				this.setIsFlying(false, PlayerSet.ofOthers());
			}
		} else {
			if (this.getTimeFlying() > INITIAL_TIME_FLYING) {
				this.setTimeFlying(this.getTimeFlying() - 1);
			}
		}
	}

	@Override
	public void onFlown(final PlayerEntity player, final ItemStack wings, final Vector3d direction) {
		if (!wings.isEmpty()) {
			FlightApparatuses.get(wings).ifPresent(apparatus -> {
				if (this.isFlying()) {
					apparatus.onFlight(player, wings, direction);
				} else if (player.position().y() < -0.5D) {
					apparatus.onLanding(player, wings, direction);
				}
			});
		}
	}

	@Override
	public void clone(final Flight other) {
		this.setIsFlying(other.isFlying());
		this.setTimeFlying(other.getTimeFlying());
	}

	@Override
	public void sync(final PlayerSet players) {
		this.syncListeners.forEach(SyncListener.onSyncUsing(players));
	}

	@Override
	public void serialize(final PacketBuffer buf) {
		buf.writeBoolean(this.isFlying());
		buf.writeVarInt(this.getTimeFlying());
	}

	@Override
	public void deserialize(final PacketBuffer buf) {
		this.setIsFlying(buf.readBoolean());
		this.setTimeFlying(buf.readVarInt());
	}

	public static final class Serializer implements NBTSerializer<FlightDefault, CompoundNBT> {
		private static final String IS_FLYING  = "isFlying";

		private static final String TIME_FLYING = "timeFlying";

		private final Supplier<FlightDefault> factory;

		public Serializer(final Supplier<FlightDefault> factory) {
			this.factory = factory;
		}

		@Override
		public CompoundNBT serialize(final FlightDefault instance) {
			final CompoundNBT compound = new CompoundNBT();
			compound.putBoolean(IS_FLYING, instance.isFlying());
			compound.putInt(TIME_FLYING, instance.getTimeFlying());
			return compound;
		}

		@Override
		public FlightDefault deserialize(final CompoundNBT compound) {
			final FlightDefault f = this.factory.get();
			f.setIsFlying(compound.getBoolean(IS_FLYING));
			f.setTimeFlying(compound.getInt(TIME_FLYING));
			return f;
		}
	}

	private final class WingState {
		private final Item item;

		private final FlightApparatus.FlightState activity;

		private WingState(final Item item, final FlightApparatus.FlightState activity) {
			this.item = item;
			this.activity = activity;
		}

		private WingState next() {
			return FlightDefault.this.voidState;
		}

		private WingState next(final ItemStack stack, final FlightApparatus wf) {
			final Item item = stack.getItem();
			if (this.item.equals(item)) {
				return this;
			}
			return new WingState(item, wf.createState(FlightDefault.this));
		}

		private void onUpdate(final PlayerEntity player, final ItemStack stack) {
			this.activity.onUpdate(player, stack);
		}
	}
}
