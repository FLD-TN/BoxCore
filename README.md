# BoxCore

Plugin lõi của server BoxPvP (Paper 1.21.1, Java 21). Phiên bản 1.1.0: **boost** (bội số nguyên theo loại).

Công thức (mỗi người chơi, mỗi loại; nguồn không có tính là 0):

```
hiệu lực = min( max(rank + chai, global, 1), max-multiplier )
```

Rank cộng dồn với chai cá nhân; mỗi loại chỉ có **1 chai đang chạy** (chai cao hơn thay chai cũ); global không cộng dồn.

Đặc tả: [BOXCORE_SPEC.md](BOXCORE_SPEC.md) + [BOXCORE_SPEC_UPDATE_01.md](BOXCORE_SPEC_UPDATE_01.md). Tài liệu cho plugin khác tích hợp: [INTEGRATION.md](INTEGRATION.md).

## Build

```
./gradlew build                 # compile + unit test
./gradlew publishToMavenLocal   # cài com.dex:boxcore-api:1.0.0 vào ~/.m2
```

Kết quả:

| File | Dùng cho |
|---|---|
| `boxcore-plugin/build/libs/BoxCore-1.1.0.jar` | bỏ vào `plugins/` của server (đã chứa API, không relocate) |
| `boxcore-api/build/libs/boxcore-api-1.0.0.jar` | cho CataMines / BoxQuest compile (`provided` / `compileOnly`); API không đổi ở 1.1.0 |

Plugin test tích hợp (chỉ compile với API qua `mavenLocal()`): `examples/boxcore-test-plugin` — chạy `../../gradlew build` trong thư mục đó, lệnh trong game `/boxcoretest [player]`.

## Lệnh

| Lệnh | Quyền |
|---|---|
| `/booster` | `boxcore.use` (mặc định true) |
| `/boxcore give <player> <type> <multiplier> <duration> [amount]` | `boxcore.admin` |
| `/boxcore global start <type> <multiplier> <duration>` | `boxcore.admin` |
| `/boxcore global stop <type>` | `boxcore.admin` |
| `/boxcore reload` | `boxcore.admin` |
| `/boxcore check <player>` | `boxcore.admin` |

Quyền rank: `boxcore.boost.<type>.<n>` (ví dụ `boxcore.boost.mining.5`). Placeholder: `%boxcore_multiple_<type>%`.

## File dữ liệu (`plugins/BoxCore/`)

- `config.yml`, `messages.yml` — cấu hình và toàn bộ text (tiếng Việt, small caps bật mặc định). Có khóa `config-version: 2`; file của bản 1.0.0 (không có khóa này) được tự động sao lưu thành `*.1.0.0.bak` và thay bằng mặc định mới khi khởi động.
- `global-boosts.yml`, `player-boosts.yml` — boost đang chạy, lưu theo thời điểm hết hạn tuyệt đối (giờ thực). File hỏng sẽ được đổi tên thành `*.broken-<timestamp>`.
