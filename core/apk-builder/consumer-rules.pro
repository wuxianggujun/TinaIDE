# ============================================================================
# BouncyCastle — JCA/JCE Provider 内部反射 + ServiceLoader
# ============================================================================
# 本模块通过 JcaX509v3CertificateBuilder / JcaContentSignerBuilder 构造证书
# 并签名（"SHA256withRSA"、"RSA"）。BC 在 JcaContentSignerBuilder.build()
# 内部会按算法字符串反射/SPI 查找实现类；若相关类被 R8 tree-shake 或重命名,
# release 构建在生成/签名 keystore 时会抛 NoSuchAlgorithmException /
# NoClassDefFoundError。
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.cert.jcajce.** { *; }
-keep class org.bouncycastle.operator.jcajce.** { *; }

# 桌面 JDK JNDI/Naming API，BC 在 Android 上不会走到，抑制告警。
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**

# ============================================================================
# Android apksig — ASN.1 注解反射解析证书/签名块
# ============================================================================
# apksig 的 Asn1BerParser / Asn1DerEncoder 依赖运行时注解和反射字段顺序。
# Release 下 R8 不能优化这条 ASN.1 反射链，否则会出现：
# 1) "SubjectPublicKeyInfo is not annotated with Asn1Class"
# 2) Asn1BerParser.lambda$parseSequence$0 排序 @Asn1Field 时 NPE
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

# 保留 ASN.1 注解、解析器、编码器本身，避免 R8 内联/优化后破坏反射过滤逻辑。
-keep class com.android.apksig.internal.asn1.** { *; }

# 保留所有 ASN.1 模型类及其字段；这些字段由 apksig 通过反射读写。
-keep @com.android.apksig.internal.asn1.Asn1Class class com.android.apksig.internal.** { *; }

# 额外兜底：即使未来模型类不在 internal 包下，也保留被 @Asn1Field 标记的字段。
-keepclassmembers class * {
    @com.android.apksig.internal.asn1.Asn1Field <fields>;
}

# ============================================================================
# ARSCLib — resources.arsc / AndroidManifest 解析
# ============================================================================
# Android/XML 桩类在本模块的 androidArscLibJar 任务中移除，由系统提供。
# 不要用 keep 桩类或关闭 R8 优化代替这层依赖隔离。
# 主路径为直接 API 调用（ApkModule / AndroidManifestBlock / ResXmlElement / ValueType），
# 无反射；保留警告抑制作为安全网。若后续使用 ARSCLib 的序列化/反序列化能力再补 -keep。
-dontwarn com.reandroid.**
