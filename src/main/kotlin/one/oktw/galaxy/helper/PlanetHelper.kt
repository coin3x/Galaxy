package one.oktw.galaxy.helper

import one.oktw.galaxy.Main.Companion.main
import one.oktw.galaxy.types.Planet
import org.spongepowered.api.Sponge
import org.spongepowered.api.world.World
import org.spongepowered.api.world.WorldArchetypes
import org.spongepowered.api.world.storage.WorldProperties
import java.io.IOException
import java.io.UncheckedIOException
import java.util.*
import java.util.concurrent.CompletableFuture

class PlanetHelper {
    companion object {
        private val logger = main.logger
        private val server = Sponge.getServer()

        fun createPlanet(name: String): Planet {
            if (server.getWorldProperties(name).isPresent)
                throw IllegalArgumentException("World already exists")
            if (!name.matches(Regex("[a-z0-9]+", RegexOption.IGNORE_CASE)))
                throw IllegalArgumentException("Name only allow a~z and 0~9")

            val properties: WorldProperties
            logger.info("Create World [{}]", name)

            try {
                properties = server.createWorldProperties(name, WorldArchetypes.OVERWORLD)
                properties.setKeepSpawnLoaded(false)
                properties.setGenerateSpawnOnLoad(false)
                properties.setLoadOnStartup(false)
                server.saveWorldProperties(properties)
            } catch (e: IOException) {
                logger.error("Create world failed!", e)
                throw UncheckedIOException(e)
            }

            val planet = Planet(world = properties.uniqueId, name = name)
            updatePlanet(planet)

            return planet
        }

        fun removePlanet(worldUUID: UUID): CompletableFuture<Boolean> {
            val properties: WorldProperties
            if (server.getWorldProperties(worldUUID).isPresent) {
                properties = server.getWorldProperties(worldUUID).get()
            } else {
                return CompletableFuture.completedFuture(true)
            }

            logger.info("Deleting World [{}]", properties.worldName)
            if (server.getWorld(worldUUID).isPresent) {
                val world = server.getWorld(worldUUID).get()
                world.players.parallelStream()
                    .forEach { it.setLocationSafely(server.getWorld(server.defaultWorldName).get().spawnLocation) }
                server.unloadWorld(world)
            }

            return server.deleteWorld(properties)
        }

        fun loadPlanet(planet: Planet): Optional<World> {
            val world = planet.world

            return if (server.getWorldProperties(world).isPresent) {
                planet.lastTime = Date()

                val worldProperties = server.getWorldProperties(world).get()
                worldProperties.setGenerateSpawnOnLoad(false)

                server.loadWorld(worldProperties)
            } else {
                Optional.empty()
            }
        }

        fun updatePlanet(planet: Planet) {
            server.getWorldProperties(planet.world).ifPresent { it.worldBorderDiameter = (planet.size * 16).toDouble() }
        }
    }
}
