# ADR-0013 — Chống bấm nhầm Đăng xuất, nuôi acc riêng cho kiếm tiền, làm sạch nhật ký ở lớp hiển thị (v1.11.0)

## Bối cảnh
Tool đôi khi bấm "Đăng xuất" khi cần chuyển acc. Nguyên nhân gốc là khớp chuỗi quá rộng (`contains("x")` trúng "Đăng xuất"), nhận diện popup chuyển acc quá chặt (≥ 2 username) và giả định cứng "dòng đầu = acc đang dùng". Cùng lúc cần thêm điều khiển nuôi acc riêng cho kiếm tiền, ngưỡng số tim, và nhật ký bớt thuật ngữ.

## Quyết định
1. **Nhãn Đăng xuất là "vùng cấm" ở tầng tìm node**: `NodeTraverser.isLogoutLabel()`; mọi tìm kiếm kiểu "chứa" (`findByText`, `findByContentDesc`, `findCloseButton`) bỏ qua nó. Chặn ở tầng thấp thay vì vá từng nơi gọi.
2. **Một đường duy nhất để mở danh sách chuyển acc** (`openSwitchList()`): tìm hàng thận trọng → đứng yên → ACTION_CLICK/chặn toạ độ trúng Đăng xuất → xác nhận kết quả → huỷ hộp đăng xuất nếu lỡ. Thay 5 đoạn lặp.
3. **Chọn dòng theo acc đang dùng, không theo vị trí** (`pickSwitchEntry`, hàm thuần có test); `activeAcc` do `detectCurrentAccount()` ghi đè mỗi lần.
4. **Bộ thiết lập nuôi acc kiếm tiền tách riêng** (`taskFarm*`), mặc định theo giá trị Nuôi TikTok lúc nâng cấp. Chọn tách thay vì dùng chung để chỉnh kiếm tiền không làm đổi chế độ Nuôi TikTok.
5. **Đọc dữ liệu video bằng bộ quét thuần Kotlin theo vị trí** (`VideoScanner`), không dò resource-id (đổi theo bản TikTok). Không đọc được số tim → không tim (an toàn hơn đoán).
6. **Làm sạch nhật ký ở lớp HIỂN THỊ** (`LogPolish`), không ở nơi ghi log: ~200 điểm gọi `log()` giữ nguyên, Room lưu bản gốc nên "Chi tiết" vẫn gỡ lỗi được; luật viết lại nằm một chỗ, test được.

## Hệ quả
- Câu chữ nhật ký mới nằm ở bảng luật `LogPolish`; thêm log mới có tag chưa biết vẫn hiện (nhãn tự sinh) nhưng nên thêm luật.
- `LogPolish` nhớ 1.500 kết quả (LRU) vì màn Nhật ký dựng lại 500 dòng mỗi lần có log mới.
- Dòng `detail` không lên popup: muốn xem phải mở Nhật ký → "Chi tiết".
