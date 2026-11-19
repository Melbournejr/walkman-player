# Kiểm thử v11.9.0

## Môi trường

Linux x86_64, JDK 17, Gradle 8.4, Android SDK 34, NDK r26b và CMake 3.22.1. APK release nhắm ARM64. Môi trường này thiếu điện thoại Android, DAC cắm trực tiếp và thiết bị âm thanh để đo.

## Kết quả

| Kiểm thử | Kết quả |
| --- | --- |
| `assembleRelease` | Tạo APK ARM64, package `com.sony.walkmanpro`, versionCode 1190, minSdk 26 và targetSdk 34. APK production tắt debuggable. |
| `lintRelease` | 0 lỗi. Vẫn có cảnh báo dependency, tài nguyên và Compose lint registry của toolchain hiện tại. |
| JVM `testReleaseUnitTest` | 14/14. PCM Direct giữ nguyên byte ở 16/24/32-bit và float trên sáu sample rate. DSP float xử lý đủ kênh, kể cả khi sink chỉ nhận một phần buffer. |
| DSP | Sine 1 kHz kiểm tra độ suy hao RMS theo biquad ở bảy sample rate. Impulse, silence, full-scale, sweep, white noise và pink noise đã lọc cho kết quả hữu hạn trong ±1; kênh im lặng giữ giá trị 0. Đã thử thay đổi pha và balance. |
| DSD header | DSF nhiều tốc độ và file lỗi, DFF thông thường, thời lượng DST theo FRTE. |
| JNI trên Linux | 14 tình huống với cùng mã C++ và FFmpeg 7.1.5. DSF DSD64/128/256, DFF DSD và DST thực giải mã thành PCM hữu hạn. Seek 500 ms và 60 ms lần lượt khớp ở file tổng hợp và mẫu DST. Đã thử seek về 0 giữa luồng, hủy, EOF và header lỗi. |
| OTA và queue | So phiên bản, thứ tự bản thử nghiệm, origin URL, SHA-256 thay đổi dù cùng kích thước, khôi phục URI hoặc index cũ và chuyển bài. |
| Workflow | `actionlint`, `bash -n` và `git diff --check` đạt. |

Chạy JNI host bằng `scripts/test-native-dsd.sh` với `JAVA_HOME` và `FFMPEG_HOST_PREFIX` trỏ tới bộ phát triển FFmpeg 7.1.5 trên Linux. Script tạo DSF/DFF tổng hợp và đối chiếu checksum mẫu DST của FFmpeg FATE.

## Kiểm chứng trên thiết bị

Cần kiểm tra cài đè v11.8.5 và luồng PackageInstaller/OTA, audio focus, Bluetooth, USB route/hot-plug, wake lock, đóng Activity, tắt màn hình lâu và chơi playlist dài. DSP dùng bộ lọc nhân quả theo từng mẫu và chuyển cấu hình trong 8 ms. Chưa đo độ trễ thực tế, THD+N, pin, sample rate vật lý hoặc tính toàn vẹn tín hiệu qua DAC.
