# BoxCore

Plugin lõi của server BoxPvP (Paper 1.21.1, Java 21). Phiên bản 1.3.0: **boost** (bội số nguyên theo loại) và tiền tệ **GEM**.

Công thức (mỗi người chơi, mỗi loại; nguồn không có tính là 0):

```
hiệu lực = min( max(rank + chai, global, 1), max-multiplier )
```

Rank cộng dồn với chai cá nhân; mỗi loại chỉ có **1 chai đang chạy** (chai cao hơn thay chai cũ); global không cộng dồn.

Đặc tả: [BOXCORE_SPEC.md](BOXCORE_SPEC.md) + [BOXCORE_SPEC_UPDATE_01.md](BOXCORE_SPEC_UPDATE_01.md) + [BOXCORE_SPEC_UPDATE_03.md](BOXCORE_SPEC_UPDATE_03.md) (Gem; bản 1.2.0 bị bỏ qua). Tài liệu cho plugin khác tích hợp: [INTEGRATION.md](INTEGRATION.md).

## Build

```
./gradlew build                 # compile + unit test
./gradlew publishToMavenLocal   # cài com.dex:boxcore-api:1.1.0 vào ~/.m2
```

Kết quả:

| File | Dùng cho |
|---|---|
| `boxcore-plugin/build/libs/BoxCore-1.3.0.jar` | bỏ vào `plugins/` của server (đã chứa API, không relocate) |
| `boxcore-api/build/libs/boxcore-api-1.1.0.jar` | cho plugin khác compile (`provided` / `compileOnly`); 1.1.0 chỉ thêm `GemService`, plugin build với 1.0.0 vẫn chạy |

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
| `/boxcore gem give\|take\|set <player> <amount> [reason] [-s]` | `boxcore.admin` (chạy được từ console, cả người chơi offline) |
| `/boxcore gem check <player>` | `boxcore.admin` |
| `/gem` (bí danh `/gems`, `/ngoc`) | `boxcore.gem.use` (mặc định true) |
| `/gem top` | `boxcore.gem.use` |
| `/gem pay <player> <amount>` | `boxcore.gem.pay` (mặc định **false**, cấp bằng LuckPerms) |

Quyền rank: `boxcore.boost.<type>.<n>` (ví dụ `boxcore.boost.mining.5`).

`-s`: không báo cho người nhận. Ví dụ thưởng quest bằng lệnh console: `boxcore gem give {player} 50 quest:mine-t3`.

Placeholder:

| Placeholder | Giá trị |
|---|---|
| `%boxcore_multiple_<type>%` | bội số boost hiệu lực |
| `%boxcore_gem%` | số dư Gem, số thô (`1250`) |
| `%boxcore_gem_formatted%` | `1.250` |
| `%boxcore_gem_short%` | `1,2K`, `3,4M` |
| `%boxcore_gem_name%` | tên hiển thị của Gem |
| `%boxcore_gem_top_<n>_name%`, `%boxcore_gem_top_<n>_amount%` | hạng `n` của bảng xếp hạng |

## File dữ liệu (`plugins/BoxCore/`)

- `config.yml`, `messages.yml` — cấu hình và toàn bộ text (tiếng Việt, small caps bật mặc định). Có khóa `config-version: 2`; file của bản 1.0.0 (không có khóa này) được tự động sao lưu thành `*.1.0.0.bak` và thay bằng mặc định mới khi khởi động.
- `global-boosts.yml`, `player-boosts.yml` — boost đang chạy, lưu theo thời điểm hết hạn tuyệt đối (giờ thực). File hỏng sẽ được đổi tên thành `*.broken-<timestamp>`.
- `gems.db` (SQLite) — số dư Gem (`gem_balances`) và sổ giao dịch (`gem_transactions`, xóa bản ghi cũ hơn `gem.log-keep-days`). Sao lưu khi server tắt, hoặc chép kèm `gems.db-wal` và `gems.db-shm`. File hỏng **không** bao giờ bị xóa: Gem tạm tắt, boost vẫn chạy. Server nâng cấp từ 1.1.0 không cần sửa `config.yml`: thiếu mục `gem` thì dùng mặc định.
