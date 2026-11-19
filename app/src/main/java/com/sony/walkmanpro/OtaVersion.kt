package com.sony.walkmanpro

internal data class OtaVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    private val prerelease: List<String>
) : Comparable<OtaVersion> {
    val isPrerelease: Boolean get() = prerelease.isNotEmpty()

    override fun compareTo(other: OtaVersion): Int {
        val core = compareValuesBy(this, other, OtaVersion::major, OtaVersion::minor, OtaVersion::patch)
        if (core != 0) return core
        if (isPrerelease != other.isPrerelease) return if (isPrerelease) -1 else 1
        for (i in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease[i]
            val b = other.prerelease[i]
            val aNumeric = a.all(Char::isDigit)
            val bNumeric = b.all(Char::isDigit)
            val order = when {
                aNumeric && bNumeric -> compareValuesBy(a, b, String::length, { it })
                aNumeric != bNumeric -> if (aNumeric) -1 else 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex("""v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""")

        fun parse(value: String): OtaVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val (major, minor, patch, suffix) = match.destructured
            val prerelease = if (suffix.isEmpty()) emptyList() else suffix.split('.')
            if (prerelease.any { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }) return null
            return OtaVersion(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null, prerelease)
        }
    }
}
