/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package ffmpeg

import Arch
import Os
import nativebuild.AndroidAbi
import nativebuild.androidNdkHostTag
import nativebuild.androidTargetName
import nativebuild.resolveNdkDir
import nativebuild.toMsysPath

/**
 * Complete build description of one FFmpeg target platform.
 *
 * Everything that varies per platform lives on this class and is produced by the factory
 * functions below — configure flags, toolchain/shell, and the assemble-stage behavior
 * (JNI wrapper, runtime DLL collection, install-name rewriting). Task registration and
 * the task implementations are platform-agnostic consumers of this data.
 */
internal data class FfmpegBuildTarget(
    val name: String,
    /** Appended to [commonConfigureFlags]; the full per-target `./configure` argument list. */
    val configureFlags: List<String>,
    val env: Map<String, String> = emptyMap(),
    val shell: String = "bash",
    val libExtension: String,
    val libPrefix: String = "lib",
    val msys2Packages: List<String> = emptyList(),

    /**
     * Version probe command lines whose combined output identifies the toolchain for the
     * build cache (see [nativebuild.ToolchainFingerprintValueSource]). Probes must be
     * runnable directly by the JVM (native paths, no MSYS path forms).
     */
    val toolchainProbes: List<List<String>> = emptyList(),

    // ---- assemble-stage behavior ----

    /** JVM JNI wrapper library file name, or null when the target has no JVM runtime (iOS). */
    val jniWrapperName: String? = null,
    /** Linker mode flags for the JNI wrapper, e.g. `-shared` or `-dynamiclib` + install_name. */
    val jniWrapperLinkFlags: List<String> = listOf("-shared"),
    /** Extra libraries appended to the JNI wrapper link line. */
    val jniWrapperExtraLibs: List<String> = emptyList(),
    /** Whether the JNI wrapper compiles against the host JDK headers (false on Android). */
    val jniWrapperUseJdkIncludes: Boolean = true,
    /** MSYS2 subsystem used when building on a Windows host ("ucrt64" or "clangarm64"). */
    val msysSubsystem: String = "ucrt64",
    /** Bundle the MSYS2 TLS CA store and recursively collect dependency DLLs (Windows targets). */
    val collectWindowsRuntime: Boolean = false,
    /** Rewrite Mach-O install names to @loader_path after assembly (Apple targets). */
    val rewriteAppleInstallNames: Boolean = false,
    /** Copy the ffmpeg CLI binary into the runtime output when the build produced one. */
    val bundleFfmpegExecutable: Boolean = true,

    // ---- dav1d (AV1 software decoding) ----

    /**
     * Build dav1d from the `mediamp-ffmpeg/dav1d` submodule and statically link it into
     * libavcodec (`--enable-libdav1d --enable-decoder=libdav1d`). Required for software
     * AV1 playback: FFmpeg 8 removed the native AV1 software decoder, leaving
     * `libavcodec/av1dec.c` as a hwaccel-only shim that fails with ENOSYS when no AV1
     * hardware decoder exists on the machine.
     */
    val dav1dEnabled: Boolean = false,
    /** Extra `meson setup` arguments for the dav1d build (e.g. macOS version-min flags). */
    val dav1dMesonArgs: List<String> = emptyList(),
    /** MSYS2 packages the dav1d meson build needs (Windows targets). */
    val dav1dMsys2Packages: List<String> = emptyList(),
)

// ---------------------------------------------------------------------------------------
// Shared configure flag groups
// ---------------------------------------------------------------------------------------

/**
 * Shared audio playback feature set. Video codecs are omitted; mixed-media containers
 * remain enabled so their audio tracks can still be played (`--disable-everything`).
 */
internal val commonConfigureFlags: List<String> = buildList {
    add("--disable-static")
    add("--enable-shared")
    add("--enable-ffmpeg")
    add("--disable-ffplay")
    add("--disable-ffprobe")
    add("--disable-doc")
    add("--disable-everything")
    add("--enable-decoder=pcm_s16le")
    add("--enable-encoder=aac")
    add("--enable-encoder=srt")
    add("--enable-decoder=aac")
    add("--enable-decoder=opus")
    add("--enable-decoder=mp3")
    add("--enable-decoder=flac")
    // Monkey's Audio (.ape).
    add("--enable-decoder=ape")
    // Dolby Digital (AC-3) and Dolby Digital Plus (E-AC-3), common audio tracks in
    // WEB-DLs and BDRips. eac3 selects the ac3 decoder in configure; both are listed
    // explicitly for clarity.
    add("--enable-decoder=ac3")
    add("--enable-decoder=eac3")
    // DTS incl. DTS-HD MA (the dca decoder handles core + lossless extensions).
    add("--enable-decoder=dca")
    // Dolby TrueHD (BD remuxes; Atmos carriers decode as TrueHD).
    add("--enable-decoder=truehd")
    // Vorbis audio in older Matroska releases; the ogg demuxer is already enabled
    // but had no matching decoder.
    add("--enable-decoder=vorbis")
    // LPCM variants beyond pcm_s16le: Blu-ray LPCM in m2ts and 24-bit PCM in mkv.
    add("--enable-decoder=pcm_bluray")
    add("--enable-decoder=pcm_s24le")
    // Common WAV variants: 32-bit integer, floating point, and G.711 companding.
    add("--enable-decoder=pcm_s32le")
    add("--enable-decoder=pcm_f32le")
    add("--enable-decoder=pcm_f64le")
    add("--enable-decoder=pcm_alaw")
    add("--enable-decoder=pcm_mulaw")
    // Japanese broadcast TS uses LATM/LOAS AAC, which the plain aac decoder does
    // not accept.
    add("--enable-decoder=aac_latm")
    // WMA audio for WMV/ASF files.
    add("--enable-decoder=wmav1")
    add("--enable-decoder=wmav2")
    add("--enable-decoder=wmapro")
    // RealAudio codecs found in rmvb: cook is the common one, sipr appears in
    // some speech-heavy rips, atrac3 in pre-rmvb .rm files.
    add("--enable-decoder=cook")
    add("--enable-decoder=sipr")
    add("--enable-decoder=atrac3")
    // DVD / DVB broadcast audio; shares the mpegaudio core with mp3.
    add("--enable-decoder=mp2")
    // Early-2000s AVI audio tracks and old QuickTime PCM.
    add("--enable-decoder=adpcm_ms")
    add("--enable-decoder=adpcm_ima_wav")
    add("--enable-decoder=pcm_u8")
    add("--enable-decoder=pcm_s16be")
    // Apple Lossless in m4a/mov.
    add("--enable-decoder=alac")
    // Subtitle decoders. The Matroska/mov demuxers still enumerate subtitle streams
    // without these, so track pickers list tracks that then fail to open — mpv logs
    // "Could not find subtitle decoder for format 'subrip'" and silently deselects
    // the track (open-ani/animeko#1128).
    add("--enable-decoder=ass")
    add("--enable-decoder=ssa")
    add("--enable-decoder=srt")
    add("--enable-decoder=subrip")
    add("--enable-decoder=text")
    add("--enable-decoder=webvtt")
    // configure component names come from ff_<name>_decoder symbols: the MP4 timed-text
    // decoder is "movtext" here even though `ffmpeg -decoders` lists it as "mov_text"
    // (unknown --enable-decoder values are silently ignored).
    add("--enable-decoder=movtext")
    add("--enable-decoder=pgssub")
    add("--enable-decoder=dvdsub")
    add("--enable-decoder=sami")
    add("--enable-decoder=microdvd")
    add("--enable-muxer=mp4")
    add("--enable-muxer=matroska")
    add("--enable-muxer=mpegts")
    add("--enable-demuxer=mpegts")
    add("--enable-demuxer=mov")
    add("--enable-demuxer=matroska")
    add("--enable-demuxer=flac")
    add("--enable-demuxer=wav")
    add("--enable-demuxer=ape")
    add("--enable-demuxer=mp3")
    add("--enable-demuxer=ogg")
    add("--enable-demuxer=aac")
    add("--enable-demuxer=concat")
    // WMV/WMA container.
    add("--enable-demuxer=asf")
    // Legacy fansub releases: XviD/DivX in AVI.
    add("--enable-demuxer=avi")
    // RealMedia container (.rm/.rmvb).
    add("--enable-demuxer=rm")
    // External subtitle files (mpv loads them through libavformat).
    add("--enable-demuxer=ass")
    add("--enable-demuxer=srt")
    add("--enable-demuxer=webvtt")
    // External VobSub (.idx/.sub pairs, AVI-era releases; selects mpegps_demuxer),
    // SAMI (.smi, Korean fansubs) and MicroDVD (text .sub).
    add("--enable-demuxer=vobsub")
    add("--enable-demuxer=sami")
    add("--enable-demuxer=microdvd")
    // Local encrypted HLS still needs the HLS demuxer so FFmpeg can interpret
    // EXT-X-KEY metadata instead of treating segments as plain concatenated TS.
    add("--enable-demuxer=hls")
    add("--enable-protocol=file")
    add("--enable-protocol=pipe")
    add("--enable-protocol=concat")
    add("--enable-protocol=crypto")
    add("--enable-parser=aac")
    add("--enable-parser=aac_latm")
    add("--enable-parser=ac3")
    add("--enable-parser=dca")
    add("--enable-parser=mlp")
    add("--enable-parser=opus")
    add("--enable-parser=mpegaudio")
    add("--enable-parser=flac")
    add("--enable-parser=vorbis")
    add("--enable-bsf=aac_adtstoasc")
    add("--enable-filter=aresample")
    add("--enable-filter=concat")
    add("--enable-filter=sine")
    add("--enable-filter=anull")
    add("--enable-indev=lavfi")
    add("--enable-swresample")
    // libmpv still links against libswscale even when video codecs are omitted.
    add("--enable-swscale")
    add("--disable-debug")
    add("--disable-autodetect")
}

private val httpTlsProtocolFlags: List<String> = listOf(
    "--enable-protocol=udp",
    "--enable-protocol=tcp",
    "--enable-protocol=tls",
    "--enable-protocol=http",
    "--enable-protocol=https",
    // tls opens via httpproxy instead of tcp when an HTTP proxy (option/env) is set.
    "--enable-protocol=httpproxy",
)

private val opensslHttpTlsFlags: List<String> = listOf(
    "--enable-openssl",
) + httpTlsProtocolFlags

private val secureTransportHttpTlsFlags: List<String> = listOf(
    "--enable-securetransport",
) + httpTlsProtocolFlags

private val linuxRuntimeSearchPathFlags: List<String> = listOf(
    "--extra-ldflags=-Wl,-rpath,'${'$'}ORIGIN'",
)

// ---------------------------------------------------------------------------------------
// Windows
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.hostWindowsTarget(): FfmpegBuildTarget = when (hostArch) {
    Arch.AARCH64 -> windowsArm64Target()
    else -> windowsX64Target()
}

private fun FfmpegBuildContext.windowsX64Target(): FfmpegBuildTarget {
    val msys2Packages = listOf(
        "make",
        "diffutils",
        "pkg-config",
        "mingw-w64-ucrt-x86_64-ca-certificates",
        "mingw-w64-ucrt-x86_64-gcc",
        "mingw-w64-ucrt-x86_64-nasm",
        "mingw-w64-ucrt-x86_64-openssl",
    )
    return FfmpegBuildTarget(
        name = "WindowsX64",
        configureFlags = listOf(
            "--arch=x86_64",
            "--target-os=mingw32",
            "--cc=${msys2Dir.resolve("ucrt64/bin/gcc.exe").absolutePath.toMsysPath()}",
            "--cxx=${msys2Dir.resolve("ucrt64/bin/g++.exe").absolutePath.toMsysPath()}",
        ) + opensslHttpTlsFlags,
        env = mapOf("MSYSTEM" to "UCRT64"),
        shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
        libExtension = "dll",
        libPrefix = "",
        msys2Packages = msys2Packages,
        toolchainProbes = listOf(
            listOf(msys2Dir.resolve("usr/bin/pacman.exe").absolutePath, "-Q") + msys2Packages,
        ),
        jniWrapperName = "ffmpegkitjni.dll",
        jniWrapperExtraLibs = listOf("-lstdc++"),
        msysSubsystem = "ucrt64",
        collectWindowsRuntime = true,
    )
}

private fun FfmpegBuildContext.windowsArm64Target(): FfmpegBuildTarget {
    val msys2Packages = listOf(
        "make",
        "diffutils",
        "pkg-config",
        "mingw-w64-clang-aarch64-ca-certificates",
        "mingw-w64-clang-aarch64-clang",
        "mingw-w64-clang-aarch64-nasm",
        "mingw-w64-clang-aarch64-openssl",
        "mingw-w64-clang-aarch64-pkgconf",
    )
    return FfmpegBuildTarget(
        name = "WindowsArm64",
        configureFlags = listOf(
            "--arch=aarch64",
            "--target-os=mingw32",
            "--cc=${msys2Dir.resolve("clangarm64/bin/clang.exe").absolutePath.toMsysPath()}",
            "--cxx=${msys2Dir.resolve("clangarm64/bin/clang++.exe").absolutePath.toMsysPath()}",
        ) + opensslHttpTlsFlags,
        env = mapOf("MSYSTEM" to "CLANGARM64"),
        shell = msys2Dir.resolve("usr/bin/bash.exe").absolutePath,
        libExtension = "dll",
        libPrefix = "",
        msys2Packages = msys2Packages,
        toolchainProbes = listOf(
            listOf(msys2Dir.resolve("usr/bin/pacman.exe").absolutePath, "-Q") + msys2Packages,
        ),
        jniWrapperName = "ffmpegkitjni.dll",
        jniWrapperExtraLibs = listOf("-lstdc++"),
        msysSubsystem = "clangarm64",
        collectWindowsRuntime = true,
    )
}

// ---------------------------------------------------------------------------------------
// Linux
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.linuxX64Target(): FfmpegBuildTarget = FfmpegBuildTarget(
    name = "LinuxX64",
    configureFlags = listOf(
        "--arch=x86_64",
        "--target-os=linux",
    ) + opensslHttpTlsFlags + linuxRuntimeSearchPathFlags,
    libExtension = "so",
    toolchainProbes = listOf(
        listOf("cc", "--version"),
        listOf("nasm", "--version"),
        listOf("pkg-config", "--modversion", "openssl"),
    ),
    jniWrapperName = "libffmpegkitjni.so",
)

// ---------------------------------------------------------------------------------------
// macOS
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.hostMacosTarget(): FfmpegBuildTarget = when (hostArch) {
    Arch.AARCH64 -> macosTarget(name = "MacosArm64", arch = "arm64")
    Arch.X86_64 -> macosTarget(name = "MacosX64", arch = "x86_64")
    else -> error("Failed to configure FFmpeg tasks, unknown macOS host.")
}

private fun macosTarget(name: String, arch: String): FfmpegBuildTarget = FfmpegBuildTarget(
    name = name,
    configureFlags = listOf(
        "--arch=$arch",
        "--target-os=darwin",
        "--cc=clang",
        "--cxx=clang++",
        "--extra-cflags=-arch $arch -mmacosx-version-min=12.0",
        "--extra-ldflags=-arch $arch -mmacosx-version-min=12.0",
    ) + secureTransportHttpTlsFlags,
    libExtension = "dylib",
    toolchainProbes = listOf(
        listOf("clang", "--version"),
    ),
    jniWrapperName = "libffmpegkitjni.dylib",
    jniWrapperLinkFlags = listOf("-dynamiclib", "-Wl,-install_name,@loader_path/libffmpegkitjni.dylib"),
    rewriteAppleInstallNames = true,
)

// ---------------------------------------------------------------------------------------
// iOS (static libs packaged as an Apple framework; no JVM runtime)
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.iosArm64Target(): FfmpegBuildTarget =
    iosTarget(name = "IosArm64", sdk = "iphoneos", versionMinFlag = "-miphoneos-version-min=16.0")

internal fun FfmpegBuildContext.iosSimulatorArm64Target(): FfmpegBuildTarget =
    iosTarget(name = "IosSimulatorArm64", sdk = "iphonesimulator", versionMinFlag = "-miphonesimulator-version-min=16.0")

private fun FfmpegBuildContext.iosTarget(
    name: String,
    sdk: String,
    versionMinFlag: String,
): FfmpegBuildTarget = FfmpegBuildTarget(
    name = name,
    configureFlags = listOf(
        "--disable-shared",
        "--enable-static",
        "--arch=arm64",
        "--target-os=darwin",
        "--enable-cross-compile",
        "--cc=xcrun --sdk $sdk clang",
        "--cxx=xcrun --sdk $sdk clang++",
        "--extra-cflags=-arch arm64 $versionMinFlag",
        "--extra-ldflags=-arch arm64 $versionMinFlag",
    ) + appleHostToolFlags + secureTransportHttpTlsFlags,
    shell = "bash",
    libExtension = "a",
    toolchainProbes = listOf(
        listOf("xcrun", "--sdk", sdk, "clang", "--version"),
        listOf("xcrun", "--sdk", sdk, "--show-sdk-version"),
    ),
    jniWrapperName = null,
    rewriteAppleInstallNames = true,
    bundleFfmpegExecutable = false,
)

private val FfmpegBuildContext.appleHostToolFlags: List<String>
    get() {
        val arch = when (hostArch) {
            Arch.AARCH64 -> "arm64"
            Arch.X86_64 -> "x86_64"
            Arch.UNKNOWN -> error("Unknown macOS host architecture for Apple FFmpeg build.")
        }
        val flags = "-arch $arch -mmacosx-version-min=12.0"
        return listOf(
            "--host-cc=xcrun --sdk macosx clang",
            "--host-cflags=$flags",
            "--host-ld=xcrun --sdk macosx clang",
            "--host-ldflags=$flags",
        )
    }

// ---------------------------------------------------------------------------------------
// Android
// ---------------------------------------------------------------------------------------

internal fun FfmpegBuildContext.androidTarget(abi: AndroidAbi): FfmpegBuildTarget {
    val ndkDir = project.resolveNdkDir()
    val hostTag = androidNdkHostTag(hostOs)

    val binDir = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/bin")
    require(binDir.isDirectory) {
        "NDK LLVM toolchain not found at '$binDir'."
    }

    val clangSuffix = if (hostOs == Os.Windows) ".cmd" else ""
    val cc = binDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang$clangSuffix").absolutePath
    val cxx = binDir.resolve("${abi.clangTriple}${abi.apiLevel}-clang++$clangSuffix").absolutePath
    val exeSuffix = if (hostOs == Os.Windows) ".exe" else ""
    val ar = binDir.resolve("llvm-ar$exeSuffix").absolutePath
    val nm = binDir.resolve("llvm-nm$exeSuffix").absolutePath
    val strip = binDir.resolve("llvm-strip$exeSuffix").absolutePath
    val ranlib = binDir.resolve("llvm-ranlib$exeSuffix").absolutePath
    val sysroot = ndkDir.resolve("toolchains/llvm/prebuilt/$hostTag/sysroot")

    fun String.msysIfWin(): String = if (hostOs == Os.Windows) toMsysPath() else this

    val abiSpecificFlags = when (abi.abi) {
        "x86", "x86_64" -> listOf("--disable-asm", "--disable-x86asm")
        else -> emptyList()
    }

    // .cmd wrappers need a shell on Windows; probe failures degrade to a stable marker.
    val clangProbe = if (hostOs == Os.Windows) listOf("cmd", "/c", cc, "--version") else listOf(cc, "--version")

    return FfmpegBuildTarget(
        name = androidTargetName(abi),
        libExtension = "so",
        shell = if (hostOs == Os.Windows) msys2Dir.resolve("usr/bin/bash.exe").absolutePath else "bash",
        env = if (hostOs == Os.Windows) mapOf("MSYSTEM" to "UCRT64") else emptyMap(),
        toolchainProbes = listOf(clangProbe),
        configureFlags = listOf(
            // Android keeps network disabled until a cross-compiled TLS backend
            // is wired in for this target family.
            "--disable-network",
            "--arch=${abi.arch}",
            "--target-os=android",
            "--enable-cross-compile",
            "--cc=${cc.msysIfWin()}",
            "--cxx=${cxx.msysIfWin()}",
            "--ar=${ar.msysIfWin()}",
            "--nm=${nm.msysIfWin()}",
            "--strip=${strip.msysIfWin()}",
            "--ranlib=${ranlib.msysIfWin()}",
            "--sysroot=${sysroot.absolutePath.msysIfWin()}",
            "--extra-cflags=-fPIC",
            "--enable-pic",
            "--enable-jni",
        ) + abiSpecificFlags,
        jniWrapperName = "libffmpegkitjni.so",
        jniWrapperUseJdkIncludes = false,
    )
}
