package one.oktw.galaxy.manager

import ninja.leaping.configurate.commented.CommentedConfigurationNode
import ninja.leaping.configurate.loader.ConfigurationLoader
import one.oktw.galaxy.Main.Companion.main

import java.io.IOException

class ConfigManager(private val configLoader: ConfigurationLoader<CommentedConfigurationNode>) {
    private val logger = main.logger
    lateinit var configNode: CommentedConfigurationNode
        private set

    init {
        try {
            configNode = configLoader.load()
        } catch (e: IOException) {
            logger.error("Config load error!", e)
        }

    }

    internal fun save() {
        try {
            configLoader.save(configNode)
        } catch (e: IOException) {
            logger.error("Config save error!", e)
        }
    }
}
