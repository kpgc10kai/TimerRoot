# The user service class is instantiated by Shizuku in another process.
-keep class com.kpgc.timerroot.UserService {
    public <init>();
    public <init>(android.content.Context);
}
-keep interface com.kpgc.timerroot.IUserService { *; }
