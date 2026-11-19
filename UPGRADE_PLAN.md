# Nâng cấp và kiểm tra trước khi phát hành

1. Review PR `upgrade/v11.9.0` và kết quả build ARM64. APK của PR dùng cho việc kiểm tra cấu trúc. APK cài đè cần đúng chứng thư v11.8.5.
2. Cấu hình Secrets theo SECURITY.md. Có thể chạy workflow thủ công với `sign=true` và `publish=false` để lấy APK ký dưới dạng artifact.
3. Cài v11.8.5 trên thiết bị thử, nhập nhạc, chỉnh DSP, dừng ở vị trí xác định rồi cập nhật tại chỗ. Kiểm tra app data, quyền SAF, bài hiện tại, vị trí và cấu hình. Kiểm tra luồng OTA với bản ký mới hơn trên máy thử.
4. Kiểm tra Android 8 và Android 13/14 trở lên, gồm cuộc gọi, rút tai nghe, Bluetooth, USB DAC, hai lần Play/Pause liên tiếp, kéo seek nhanh, đóng Activity, tắt màn hình và xen kẽ PCM/DSD.
5. Chỉ sau khi chủ kho duyệt mới dùng tag phiên bản và quy trình phát hành.

## USB Audio và đợt sau

Audio Lab xem thiết bị USB mà Android báo, danh sách rate do thiết bị khai báo và route thực tế nếu AudioTrack DSD báo được. Chạy `scripts/probe-usb-dac.sh` từ máy có ADB để đối chiếu audio port, active patch và mixer. Prototype này đọc diagnostics, nên có thể giữ engine phát hiện tại trong lúc đo.

Kiến trúc USB exclusive cần một prototype độc lập quản lý quyền USB Host, descriptor/interface UAC, endpoint isochronous, clock/feedback, luồng PCM ổn định và hot-plug. Bước đầu cần chứng minh thiết bị nhận 44.1/48 kHz và các bội số bằng capture DAC. Chỉ thử DoP sau khi xác nhận đường PCM giữ nguyên marker và payload. Native DSD cần DAC, giao thức và trace xác nhận riêng. Ưu tiên tiếp theo là kiểm thử thiết bị và phương án đổi khóa trước khi đưa các đường output này vào app.
