package one.oktw.galaxy.event

import com.flowpowered.math.imaginary.Quaterniond
import com.flowpowered.math.vector.Vector3d
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.util.EntityDamageSource
import one.oktw.galaxy.Main.Companion.main
import one.oktw.galaxy.Main.Companion.travelerManager
import one.oktw.galaxy.data.DataEnable
import one.oktw.galaxy.data.DataUUID
import one.oktw.galaxy.enums.ItemType
import one.oktw.galaxy.enums.ItemType.PISTOL
import one.oktw.galaxy.enums.ItemType.SNIPER
import one.oktw.galaxy.enums.UpgradeType.*
import one.oktw.galaxy.helper.CoolDownHelper
import one.oktw.galaxy.types.item.Gun
import org.spongepowered.api.block.BlockTypes.*
import org.spongepowered.api.data.key.Keys
import org.spongepowered.api.data.manipulator.mutable.entity.SneakingData
import org.spongepowered.api.data.property.entity.EyeLocationProperty
import org.spongepowered.api.data.type.HandTypes
import org.spongepowered.api.effect.particle.ParticleEffect
import org.spongepowered.api.effect.particle.ParticleTypes
import org.spongepowered.api.effect.sound.SoundCategories
import org.spongepowered.api.effect.sound.SoundType
import org.spongepowered.api.effect.sound.SoundTypes
import org.spongepowered.api.entity.living.ArmorStand
import org.spongepowered.api.entity.living.Living
import org.spongepowered.api.entity.living.monster.Boss
import org.spongepowered.api.entity.living.player.Player
import org.spongepowered.api.event.Listener
import org.spongepowered.api.event.cause.entity.damage.source.DamageSource
import org.spongepowered.api.event.data.ChangeDataHolderEvent
import org.spongepowered.api.event.filter.Getter
import org.spongepowered.api.event.filter.data.Has
import org.spongepowered.api.event.filter.type.Include
import org.spongepowered.api.event.item.inventory.ChangeInventoryEvent
import org.spongepowered.api.event.item.inventory.InteractItemEvent
import org.spongepowered.api.item.ItemTypes.DIAMOND_SWORD
import org.spongepowered.api.item.inventory.ItemStack
import org.spongepowered.api.scheduler.Task
import org.spongepowered.api.util.blockray.BlockRay
import org.spongepowered.api.world.World
import org.spongepowered.api.world.extent.EntityUniverse.EntityHit
import java.lang.Math.random
import kotlin.math.roundToInt

class Gun {
    @Listener
    @Suppress("unused")
    fun onInteractItem(event: InteractItemEvent.Secondary.MainHand, @Getter("getSource") player: Player) {
        val itemStack = event.itemStack
        if (itemStack.type != DIAMOND_SWORD || !itemStack[DataUUID.key].isPresent) return

        val world = player.world
        var direction =
            Quaterniond.fromAxesAnglesDeg(player.rotation.x, -player.rotation.y, player.rotation.z).direction
        val source = player.getProperty(EyeLocationProperty::class.java)
            .map(EyeLocationProperty::getValue).orElse(null)?.add(direction) ?: return

        launch {
            val gun = (travelerManager.getTraveler(player).item
                .filter { it is Gun }
                .find { (it as Gun).uuid == itemStack[DataUUID.key].get() } as? Gun ?: return@launch).copy()

            doUpgrade(gun).await()

            if (checkOverheat(world, source, gun).await()) return@launch

            if (!player[Keys.IS_SNEAKING].get()) {
                direction = drift(direction)
            }

            val target = getTarget(world, source, direction, gun.range).await()
            val wall = getWall(
                world,
                source.sub(direction),
                direction,
                if (!target.isEmpty()) target.first().intersection.distance(source) else gun.range
            ).await()

            if (!target.isEmpty() && !wall.hasNext()) Task.builder().execute { _ ->
                damageEntity(
                    player,
                    source,
                    gun,
                    target
                )
            }.submit(main)

            showTrajectory(
                world, source, when {
                    wall.hasNext() -> wall.next().position.sub(source)
                    !target.isEmpty() -> target.first().intersection.sub(source)
                    else -> direction.mul(gun.range)
                }
            )

            playShotSound(world, source, gun.itemType)
        }
    }

    @Listener
    @Suppress("unused", "UNUSED_PARAMETER")
    fun onChangeDataHolder(event: ChangeDataHolderEvent.ValueChange, @Getter("getTargetHolder") @Has(SneakingData::class) player: Player) {
        player.getItemInHand(HandTypes.MAIN_HAND).filter { it[DataEnable.key].isPresent }.ifPresent {
            val sneak: Boolean =
                event.endResult.successfulData.firstOrNull { it.key == Keys.IS_SNEAKING }?.get() as Boolean?
                        ?: player[Keys.IS_SNEAKING].get()
            if (it[DataEnable.key].get() != sneak) {
                player.setItemInHand(HandTypes.MAIN_HAND, toggleScope(it))
            }

            if (sneak) player.offer(Keys.WALKING_SPEED, -10.0) else player.offer(Keys.WALKING_SPEED, 0.1)
        }
    }

    @Listener
    @Include(ChangeInventoryEvent.Held::class, ChangeInventoryEvent.SwapHand::class)
    @Suppress("unused", "UNUSED_PARAMETER")
    fun onChangeInventory(event: ChangeInventoryEvent, @Getter("getSource") player: Player) {
        val mainHand = player.getItemInHand(HandTypes.MAIN_HAND).filter { it[DataEnable.key].isPresent }.orElse(null)
        mainHand?.let {
            val sneak = player[Keys.IS_SNEAKING].get()
            if (it[DataEnable.key].get() != sneak) {
                player.setItemInHand(HandTypes.MAIN_HAND, toggleScope(it))
            }

            if (sneak) player.offer(Keys.WALKING_SPEED, -10.0) else player.offer(Keys.WALKING_SPEED, 0.1)
        }

        if (mainHand == null) player.offer(Keys.WALKING_SPEED, 0.1)

        player.getItemInHand(HandTypes.OFF_HAND).filter { it[DataEnable.key].isPresent }.ifPresent {
            if (it[DataEnable.key].get()) player.setItemInHand(HandTypes.OFF_HAND, toggleScope(it))
        }
    }

    private fun drift(direction: Vector3d): Vector3d {
        return direction.mul(10.0).add(Math.random(), Math.random(), Math.random())
            .sub(Math.random(), Math.random(), Math.random()).div(10.0)
    }

    private fun doUpgrade(gun: Gun) = async {
        gun.upgrade.forEach {
            when (it.type) {
                DAMAGE -> gun.damage += it.level * 3
                RANGE -> gun.range += it.level * 5
                COOLING -> gun.cooling += it.level * 2
                THROUGH -> gun.through += it.level
                HEAT -> gun.maxTemp += it.level * 5
                else -> {
                }
            }
        }
    }

    private fun checkOverheat(world: World, source: Vector3d, gun: Gun) = async {
        var heatStatus = CoolDownHelper.getCoolDown(gun.uuid)
        if (heatStatus == null) {
            heatStatus = CoolDownHelper.HeatStatus(gun.uuid, max = gun.maxTemp, cooling = gun.cooling)
            CoolDownHelper.addCoolDown(heatStatus)
        }

        if (heatStatus.isOverheat()) return@async true

        if (heatStatus.addHeat(gun.heat)) {
            world.playSound(SoundType.of("gun.overheat"), SoundCategories.PLAYER, source, 1.0)
        }

        return@async false
    }

    private fun getTarget(world: World, source: Vector3d, direction: Vector3d, range: Double) = async {
        world.getIntersectingEntities(
            source,
            direction,
            range,
            { it.entity is Living && it.entity !is Player && it.entity !is ArmorStand && (it.entity as EntityLivingBase).isEntityAlive }
        )
    }

    private fun getWall(world: World, source: Vector3d, direction: Vector3d, range: Double) = async {
        BlockRay.from(world, source)
            .direction(direction)
            .distanceLimit(range)
            .skipFilter {
                when (it.location.blockType) {
                    AIR,
                    GLASS, STAINED_GLASS,
                    GLASS_PANE, STAINED_GLASS_PANE,
                    IRON_BARS,
                    TALLGRASS, DOUBLE_PLANT,
                    TORCH, REDSTONE_TORCH, UNLIT_REDSTONE_TORCH,
                    REEDS,
                    LEAVES, LEAVES2 -> false

                    else -> true
                }
            }.build()
    }

    private fun damageEntity(player: Player, source: Vector3d, gun: Gun, target: Set<EntityHit>) {
        target.stream()
            .filter { it.entity !is Boss }
            .sorted { hit1, hit2 -> ((hit1.intersection.distance(source) - hit2.intersection.distance(source)) * 10).roundToInt() }
            .limit(gun.through.toLong())
            .forEach {
                val entity = it.entity as Living

                val damageSource = EntityDamageSource("player", player as EntityPlayer).setProjectile() as DamageSource
                (entity as EntityLivingBase).hurtResistantTime = 0
                entity.damage(gun.damage, damageSource)
                gun.damage *= 0.9

                if ((entity as EntityLivingBase).isEntityAlive) {
                    player.playSound(
                        SoundTypes.ENTITY_EXPERIENCE_ORB_PICKUP,
                        SoundCategories.PLAYER,
                        source,
                        1.0
                    )
                } else {
                    player.playSound(
                        SoundTypes.ENTITY_EXPERIENCE_ORB_PICKUP,
                        SoundCategories.PLAYER,
                        source,
                        1.0,
                        0.5
                    )
                }
            }
    }

    private fun showTrajectory(world: World, source: Vector3d, line: Vector3d) = async {
        val interval = when (line.abs().maxAxis) {
            0 -> line.abs().x.div(0.3)
            1 -> line.abs().y.div(0.3)
            2 -> line.abs().z.div(0.3)
            else -> 10.0
        }
        var pos = source
//                .add(line.div(interval / 4))

        for (i in 0..interval.roundToInt()) {
            world.spawnParticles(
                ParticleEffect.builder()
                    .type(ParticleTypes.MAGIC_CRITICAL_HIT)
                    .build(),
                pos
            )
            pos = pos.add(line.div(interval))
        }
    }

    private fun playShotSound(world: World, position: Vector3d, type: ItemType) = async {
        when (type) {
            PISTOL -> world.playSound(
                SoundType.of("gun.shot"),
                SoundCategories.PLAYER,
                position,
                1.0,
                1 + random() / 10 - random() / 10
            )
            SNIPER -> {
                // TODO new sound
                world.playSound(
                    SoundType.of("entity.blaze.hurt"),
                    SoundCategories.PLAYER,
                    position,
                    1.0,
                    2.0
                )
                world.playSound(
                    SoundType.of("entity.firework.blast"),
                    SoundCategories.PLAYER,
                    position,
                    1.0,
                    0.0
                )
                world.playSound(
                    SoundType.of("block.piston.extend"),
                    SoundCategories.PLAYER,
                    position,
                    1.0,
                    2.0
                )
            }
            else -> Unit
        }
    }

    private fun toggleScope(item: ItemStack) = item.apply {
        transform(Keys.ITEM_DURABILITY) { if (item[DataEnable.key].get()) it - 1 else it + 1 }

        transform(DataEnable.key) { !it }
    }
}
