package xyz.lilsus.flint

object FlintApplication {
    fun createExperience(host: FlintAppHost) = SparkIosExperience(host, FLINT_EXPERIENCE)
}
