# ============================================================================
# JGit — NLS 反射 + ServiceLoader + Transport
# ============================================================================
# JGit 的 NLS 系统会通过 TranslationBundle.lookupBundle(Class)
# 反射实例化 bundle 子类，并按字段名绑定 ResourceBundle 文案。
# 仅保留类名不够，R8 仍可能删除默认构造器或改写字段名，导致 release
# 版在 JGitText / TranslationBundle 初始化阶段崩溃。
-keep class * extends org.eclipse.jgit.nls.TranslationBundle {
    <init>();
    <fields>;
}

# JGit 通过 SPI/ServiceLoader 发现 TransportProtocol 实现
# META-INF/services 文件中的类名必须与实际类名一致
-keep class * extends org.eclipse.jgit.transport.TransportProtocol

# SshSessionFactory 通过反射发现 SSH 实现
-keepnames class * extends org.eclipse.jgit.transport.SshSessionFactory

# Ed25519 key generation uses the bundled BouncyCastle provider on Android 9-12,
# where the platform JCA provider does not consistently expose Ed25519.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
-dontwarn org.bouncycastle.**

# ============================================================================
# JGit + Apache SSHD — 桌面 JDK API 警告抑制
# ============================================================================
# JGit + Apache SSHD 在 Android 上会引用部分桌面 JDK/JGSS API。
# 这些路径在 TinaIDE 的 Android 场景不会触发，按模块粒度抑制告警。
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.InstanceAlreadyExistsException
-dontwarn javax.management.InstanceNotFoundException
-dontwarn javax.management.JMException
-dontwarn javax.management.MBeanException
-dontwarn javax.management.MBeanRegistrationException
-dontwarn javax.management.MBeanServer
-dontwarn javax.management.MalformedObjectNameException
-dontwarn javax.management.NotCompliantMBeanException
-dontwarn javax.management.ObjectInstance
-dontwarn javax.management.ObjectName
-dontwarn javax.management.ReflectionException
-dontwarn javax.security.auth.login.CredentialException
-dontwarn javax.security.auth.login.FailedLoginException
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid
