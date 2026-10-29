plugins {
    alias(kei.plugins.extension)
}

yuzono {
    name = "Hentai Saturn"
    versionCode = 14
    contentWarning = ContentWarning.NSFW
    libVersion = "14"

    source {
        name = "HentaiSaturn"
        lang = "it"
        baseUrl("https://hentaisaturn.tv") {
            mirrors = listOf(
                "hentaisaturn.com",
            )
        }
    }
}

dependencies {
    implementation(project(":lib:playlistutils"))
}

