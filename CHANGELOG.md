# Changelog

## 11.9.0 dự kiến

- Đưa PCM, DSD, queue, sleep timer, audio focus và MediaSession vào playback service. Lưu trạng thái theo URI và tiếp tục phát khi Activity đóng.
- Thêm Direct Sound bypass tại buffer. DSP 10-band dùng cùng thuật toán cho PCM và DSD-to-PCM, giữ kênh PCM và xử lý cả nhánh high-resolution.
- Sửa thời lượng DSF, vòng đời giải mã, seek, pause/resume, ghi PCM và kết thúc luồng DSD. Cập nhật FFmpeg 7.1.5.
- Audio Lab báo thông số nguồn, decoder, PCM, route và resampling mà app quan sát được.
- Release ARM64 có checksum SHA-256 và kiểm tra chứng thư. OTA xác thực nguồn, APK, phiên bản và yêu cầu thao tác cài đặt riêng.
