package com.wuxianggujun.tinaide.core.packages

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class VcpkgAndroidSupportTest {
    @Test fun unrestrictedPortsAreCandidatesForBothSupportedAbis() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            expect("", abi, VcpkgPlatformSupport.SUPPORTED)
            expect(" \t\r\n", abi, VcpkgPlatformSupport.SUPPORTED)
        }
        expect("", "armeabi-v7a", VcpkgPlatformSupport.UNKNOWN)
        expect("android", "unknown", VcpkgPlatformSupport.UNKNOWN)
    }

    @Test fun androidIsDistinctFromDesktopLinuxAndOtherPlatforms() {
        for (abi in listOf("arm64-v8a", "x86_64")) {
            expect("android", abi, VcpkgPlatformSupport.SUPPORTED)
            expect("!android", abi, VcpkgPlatformSupport.UNSUPPORTED)
            for (platform in listOf("windows", "linux", "osx", "uwp", "mingw", "ios", "bsd", "apple", "ohos", "emscripten")) {
                expect(platform, abi, VcpkgPlatformSupport.UNSUPPORTED)
                expect("!$platform", abi, VcpkgPlatformSupport.SUPPORTED)
            }
        }
    }

    @Test fun armIncludesArm64ButArm32DoesNot() {
        expect("android & arm", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
        expect("android & arm64", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
        expect("android & arm32", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("!arm", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("arm64ec", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("arm", "x86_64", VcpkgPlatformSupport.UNSUPPORTED)
        expect("x64", "x86_64", VcpkgPlatformSupport.SUPPORTED)
        expect("x86", "x86_64", VcpkgPlatformSupport.UNSUPPORTED)
        expect("x64", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
    }

    @Test fun matchesStaticLibraryStaticCrtAndEqualHostTargetTriplets() {
        expect("android & static & staticcrt & native", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
        expect("!static", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("!staticcrt", "x86_64", VcpkgPlatformSupport.UNSUPPORTED)
        expect("!native", "x86_64", VcpkgPlatformSupport.UNSUPPORTED)
    }

    @Test fun parenthesesAndNegationMatchUpstreamConditions() {
        expect("(android & arm64) | (linux & x64)", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
        expect("(android & arm64) | (linux & x64)", "x86_64", VcpkgPlatformSupport.UNSUPPORTED)
        expect("!(windows | (android & !static))", "x86_64", VcpkgPlatformSupport.SUPPORTED)
        expect("!(android & (arm64 | x64))", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("!(!android)", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
    }

    @Test fun commaHasLowerPrecedenceAndKeywordsMatchBundledCli() {
        expect("!windows, mingw", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
        expect("windows & static, android & x64", "x86_64", VcpkgPlatformSupport.SUPPORTED)
        expect("windows & static, android & x64", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("(windows & !uwp), (linux, osx)", "arm64-v8a", VcpkgPlatformSupport.UNSUPPORTED)
        expect("android and not (windows | linux)", "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
    }

    @Test fun malformedExpressionsFailClosedIncludingHiddenBranches() {
        val malformed = listOf("android &", "()", "(android", "android)", "android linux", "android && x64",
            "android || linux", "android | linux & x64", "android & arm64 | x64", "!!android", "android or linux",
            "ANDROID", "android;", "android,", "android | (", "windows & ()", "android | x86_64")
        for (expression in malformed) expect(expression, "arm64-v8a", VcpkgPlatformSupport.UNKNOWN)
    }

    @Test fun unknownIdentifiersCannotBecomeSupportedThroughNegationOrShortCircuit() {
        for (expression in listOf("futureos", "!futureos", "android | futureos", "windows & futureos", "nativefeature")) {
            expect(expression, "arm64-v8a", VcpkgPlatformSupport.UNKNOWN)
        }
    }

    @Test fun untrustedExpressionsHaveSizeAndRecursionLimits() {
        expect(" ".repeat(4097), "arm64-v8a", VcpkgPlatformSupport.UNKNOWN)
        expect("(".repeat(1000) + "android" + ")".repeat(1000), "arm64-v8a", VcpkgPlatformSupport.UNKNOWN)
        expect("(".repeat(32) + "android" + ")".repeat(32), "arm64-v8a", VcpkgPlatformSupport.SUPPORTED)
    }

    private fun expect(expression: String, abi: String, expected: VcpkgPlatformSupport) {
        assertWithMessage("$abi: $expression").that(VcpkgAndroidSupport.evaluate(expression, abi)).isEqualTo(expected)
    }
}
