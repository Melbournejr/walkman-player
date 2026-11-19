# Walkman Player

Trình nghe nhạc offline cho Android 8 trở lên, ARM64. PCM phát qua Media3. DSF và DFF, kể cả DST, phát qua FFmpeg chuyển sang PCM float.

## Build

Yêu cầu JDK 17, Gradle 8.4, Android API 34, Build Tools 34.0.0, NDK 26.1.10909125 và CMake 3.22.1. Script FFmpeg chạy trên Linux.

```bash
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.1.10909125"
scripts/build-ffmpeg-android.sh
gradle testReleaseUnitTest lintRelease assembleRelease
```

APK release nằm ở `app/build/outputs/apk/release/`. Xem hướng dẫn tạo APK ký bằng khóa hiện tại. [SECURITY.md](SECURITY.md)

## Cập nhật từ 11.8.5

1. Dùng APK ARM64 đã ký bằng chứng thư v11.8.5 và đối chiếu SHA-256 bên cạnh bản build.
2. Cài đè từ trình cài đặt Android để giữ dữ liệu ứng dụng. Lần đầu dùng OTA của v11.9.0, bấm tải rồi bấm **Cài đặt** khi tải và xác minh đã hoàn tất.
3. Kiểm tra lại playlist, vị trí phát và cấu hình DSP. Cấp quyền đọc thư mục/tệp lại nếu Android yêu cầu.

Direct Sound bỏ qua DSP của app. Android và thiết bị đầu ra quyết định đường mixer, codec và tốc độ phần cứng. Audio Lab hiển thị thông số được đọc hoặc yêu cầu và dùng **Unknown** khi không xác minh được.

Mã nguồn FFmpeg n7.1.5 được liên kết động. Build script dùng commit cố định và tải thư viện từ mã nguồn theo LGPL 2.1 trở lên. [FFmpeg source and license](https://github.com/FFmpeg/FFmpeg/tree/3a0867c2bfda4a4d4309ca1a8cbdc6175e67f587)
