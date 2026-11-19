# Bảo mật và ký APK

Keystore tương ứng với APK v11.8.5 và mật khẩu của nó từng nằm trong kho công khai. Chứng thư SHA-256 của APK phát hành là `56a574706e4a8452260b27077da58dadcf6781866ff90fc733947e75d12e5530`. Xóa tệp trong cây mã mới giúp ngăn tái sử dụng ngoài ý muốn, nhưng lịch sử Git vẫn chứa khóa cũ.

## Cấu hình build đã ký

Từ **Settings → Secrets and variables → Actions**, tạo các repository secrets sau bằng bản sao khóa cài đặt hiện tại mà chủ kho đang quản lý.

| Secret | Giá trị |
| --- | --- |
| `WALKMAN_KEYSTORE_BASE64` | Base64 một dòng của keystore hiện có, ví dụ lấy bằng `base64 -w0 /path/to/current.jks` trên Linux |
| `WALKMAN_STORE_PASSWORD` | Mật khẩu kho |
| `WALKMAN_KEY_ALIAS` | Alias đúng của chứng thư hiện tại, kể cả khoảng trắng |
| `WALKMAN_KEY_PASSWORD` | Mật khẩu của khóa |

Build tại chỗ dùng bốn biến `WALKMAN_KEYSTORE_PATH`, `WALKMAN_STORE_PASSWORD`, `WALKMAN_KEY_ALIAS` và `WALKMAN_KEY_PASSWORD`. Đặt đường dẫn tuyệt đối, giữ bí mật ngoài Git và chạy `gradle assembleRelease`. Dùng `apksigner verify --min-sdk-version 26 --print-certs` để so fingerprint trước khi cài. Workflow thủ công có tùy chọn ký artifact riêng với thao tác publish.

## Chuyển tiếp sang khóa mới

Giữ `com.sony.walkmanpro` và `versionCode` tăng dần. Hãy xác nhận kế hoạch và sao lưu trước khi ký hoặc phát hành bằng khóa mới. APK Signature Scheme v3 cho phép thử signing lineage trên Android 9 trở lên. Cần kiểm tra policy rotation, hành vi trên Android 9–12 và 13 trở lên, cài đè từ APK thật và khôi phục dữ liệu trên thiết bị thử. Android 8 cần đường chuyển tiếp ký bằng khóa cũ, hoặc xuất/nhập dữ liệu nếu cài lại bằng khóa mới. Bộ xác minh OTA hiện so signer đang cài, nên OTA hỗ trợ lineage cần một thay đổi có kiểm thử riêng.

OTA chỉ nhận asset GitHub Releases đã có digest SHA-256 từ API kho cấu hình sẵn. Nếu nghi ngờ tài khoản GitHub hoặc quy trình ký bị chiếm quyền, tạm ngừng phát hành và kiểm tra nguồn phát hành trước khi xác minh APK mới.
