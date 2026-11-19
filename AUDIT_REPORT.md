# Kiểm toán Walkman v11.8.5

Baseline `main` tại `8d5eb65`. Phạm vi gồm Activity, service, thư viện, DSP, bộ giải mã DSD, JNI, OTA, manifest, Gradle, CMake và GitHub Actions.

| Mức | Phát hiện trên baseline | Xử lý trong PR |
| --- | --- | --- |
| P0 | Keystore và mật khẩu ký nằm trong Git. Gradle có thể sinh khóa thay thế khi thiếu tệp. | Bỏ keystore khỏi cây mã mới, bỏ sinh khóa, chuyển cấu hình qua biến môi trường. CI đối chiếu fingerprint với chứng thư của APK v11.8.5. Khóa cũ vẫn phải được coi là đã lộ. |
| P1 | CI phát hành APK debug khi push vào `main`; OTA tải APK từ URL cấu hình được và chỉ kiểm tra ngưỡng kích thước. | Build release, kiểm thử và lint trước khi ký. OTA giới hạn nguồn, đối chiếu digest, kích thước, package, signer và phiên bản. Cài đặt bắt đầu từ nút xác nhận riêng. |
| P1 | Receiver và service exported. Lệnh Play và Pause cùng gọi toggle. Player, listener và callback giữ Activity cũ. | Service riêng tư sở hữu cả hai engine và MediaSession. Notification dùng PendingIntent bất biến với lệnh riêng. |
| P1 | PCM float đi qua nhánh bỏ qua DSP; PCM nhiều kênh bị ghi số 0 sau cặp L/R. Direct vẫn áp dụng lọc, balance, pha và limiter. | Xử lý PCM trước nhánh float của Media3. DSP dùng trạng thái theo kênh, giữ nguyên buffer khi bypass. DSD sau giải mã dùng cùng bộ DSP. |
| P1 | DSD dùng cờ stop/seek dùng chung, chặn luồng chính bằng join, bỏ qua lỗi ghi, chưa drain decoder và resampler. Header DSF đọc sai offset. | Phiên giải mã độc lập và I/O có kiểm tra hủy, xử lý seek, ghi AudioTrack từng phần, drain trước khi báo hết bài. DSF dùng số mẫu trong header để tính thời lượng. |
| P2 | Activity cập nhật notification và ghi vị trí liên tục. Foreground service khởi động khi mở app. | Notification cập nhật theo sự kiện, lưu vị trí mỗi 15 giây khi đang phát và khi trạng thái đổi. DSD chỉ giữ wake lock khi phát. |
| P2 | Playlist cắt ở 2.000 mục, phục hồi theo index và tiêu đề, hiệu ứng tăng âm lượng cũ có thể tác động bài mới. | Giữ danh sách đầy đủ, phục hồi URI và hỗ trợ dữ liệu cũ. Fade-in thuộc controller và được hủy khi chuyển trạng thái. |
| P2 | Audio Lab gán sample rate, bit depth và codec Bluetooth theo tên file/thiết bị; USB luôn được gắn nhãn bit-perfect. | Hiển thị metadata đọc được và thông số do ứng dụng yêu cầu. Thiết bị đã kết nối và route xác nhận được có nhãn riêng. |
| P2 | FFmpeg n6.0, bộ lọc có tính lại hệ số mỗi buffer và cấu hình build dùng `-ffast-math`. | Chuyển sang n7.1.5 theo commit cố định, thu hẹp demuxer/decoder, cập nhật hệ số khi cấu hình đổi, bỏ fast math. |
| P3 | Media3 1.2.1, Compose và toolchain còn các bản mới hơn. | Bỏ Media3 Session không được gọi. Giữ phiên bản các dependency đang dùng để giới hạn phạm vi hồi quy. GitHub báo Dependabot alerts đang tắt, nên chưa có danh sách CVE đầy đủ từ kho. |

## Đường tín hiệu sau nâng cấp

- PCM đi từ extractor/decoder Media3 đến DSP theo encoding và sample rate mà sink nhận. Sau đó `DefaultAudioSink` gửi PCM 16-bit hoặc float tới AudioTrack tùy định dạng và route. PCM 24-bit có thể chuyển sang float. PCM 32-bit integer có thể mất bit thấp khi chuyển sang float.
- DSF/DFF/DST được FFmpeg giải mã rồi chuyển thành PCM float qua libswresample. AudioTrack giữ mono hoặc stereo. DSD nhiều kênh được downmix theo layout của FFmpeg và có nhãn trong Audio Lab.
- Direct Sound bỏ qua bộ DSP ứng dụng ngay tại buffer. Volume, AudioTrack, Android mixer, Bluetooth và phần cứng vẫn có thể đổi tín hiệu. Chưa xác định được sample rate vật lý, USB exclusive, DoP hay Native DSD.
- DSP dùng biquad double theo sample rate đầu vào, headroom trước chuỗi lọc và soft limiter khi xử lý. Các kênh giữ trạng thái riêng; mở rộng stereo/balance tác động cặp trước. Chuyển cấu hình DSP thông thường được trộn trong 8 ms. Direct bypass tác động ngay ở ranh giới buffer.
