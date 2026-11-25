package com.wuxianggujun.tinaide.testing;

import java.io.File;
import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Properties;

/** Uses the existing signing properties in memory; passwords never enter command-line arguments. */
public final class SignTestArtifact {
    public static void main(String[] arguments) throws Exception {
        File source = new File(arguments[0]);
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(new File(source, "keystore.properties"))) {
            properties.load(input);
        }
        File store = new File(properties.getProperty("storeFile"));
        if (!store.isAbsolute()) store = new File(source, store.getPath());
        File signerJar = new File(new File(arguments[1]).getParentFile(), "lib/apksigner.jar");
        if (!signerJar.isFile()) throw new IllegalArgumentException("SDK signer jar is missing");
        String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
        String executable = new File(System.getProperty("java.home"), "bin/java" + suffix).getAbsolutePath();
        ProcessBuilder builder = new ProcessBuilder(Arrays.asList(
                executable, "-jar", signerJar.getAbsolutePath(), "sign", "--ks", store.getAbsolutePath(),
                "--ks-key-alias", properties.getProperty("keyAlias"),
                "--ks-pass", "env:TINA_QA_STORE_PASSWORD", "--key-pass", "env:TINA_QA_KEY_PASSWORD",
                "--out", arguments[3], arguments[2]));
        builder.environment().put("TINA_QA_STORE_PASSWORD", properties.getProperty("storePassword"));
        builder.environment().put("TINA_QA_KEY_PASSWORD", properties.getProperty("keyPassword"));
        builder.inheritIO();
        System.exit(builder.start().waitFor());
    }
}
