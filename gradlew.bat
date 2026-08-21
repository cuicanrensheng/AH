@ECHO OFF
REM Gradle wrapper for Windows
set DIR=%~dp0
set JAVA_HOME=C:\Users\16937\.jdks\temurin-17
set ANDROID_HOME=C:\Users\16937\.trae-cn\work\6a73dc86173e0ef11a471500\android-sdk
set ANDROID_SDK_ROOT=%ANDROID_HOME%
set GRADLE_OPTS=-Djavax.net.ssl.trustAllCertificates=true -Djavax.net.ssl.checkServerIdentity=false -Dcom.sun.net.ssl.checkRevocation=false -Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false -Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8

"%JAVA_HOME%\bin\java.exe" %GRADLE_OPTS% -classpath "%DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
EXIT /B %ERRORLEVEL%
