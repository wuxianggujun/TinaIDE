# libXlorie 的 JNI 入口（CmdEntryPoint / LorieView）与 hidden framework 类的 -dontwarn
# 由 external/termux-x11/lorie/proguard-rules.pro 作为 consumer rules 提供，本模块不重复声明。
#
# X11ServerService、MainActivity 等组件写在本模块 manifest 里，AGP 会为合并后的
# manifest 组件自动生成 keep 规则；AIDL 的 Stub/Proxy 经 asInterface 静态可达。
# 当前模块无需额外 consumer rules。
