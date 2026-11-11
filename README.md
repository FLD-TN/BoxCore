# BoxCore

Plugin lõi của server BoxPvP, cung cấp hai thứ cho các plugin khác dùng chung: **boost** (hệ số nhân theo loại, ví dụ
mining, quest, mob) và tiền tệ **Gem**. Các plugin Box* khác đọc hệ số và trừ / cộng Gem qua API của BoxCore.

Paper 1.21.1, Java 21. PlaceholderAPI không bắt buộc.

## Boost

Mỗi loại boost khai báo trong `types` của `config.yml` (tên, màu, bossbar, hệ số tối đa, thời gian tối đa, chất liệu
chai). Loại có `decimal: true` nhận hệ số lẻ một chữ số (x1.5); loại khác chỉ nhận số nguyên.

Một người chơi có thể được boost từ 4 nguồn:

- **Rank**: quyền `boxcore.boost.<loại>.<n>`, ví dụ `boxcore.boost.mining.5`. Hệ số lẻ viết `1_5` thay cho `1.5`.
- **Chai cá nhân**: chuột phải để dùng. Mỗi loại chỉ chạy một chai. Chai cùng hệ số thì cộng thêm thời gian, chai cao
  hơn thì thay chai cũ (có hỏi xác nhận), chai thấp hơn bị từ chối và giữ lại.
- **Global**: admin bật cho cả server.
- **Bang hội** (cần BoxClans): chai bang hội, chỉ Minh Chủ / Phó Bang dùng, áp cho cả clan.

Hệ số thực tế:

```
hiệu lực = min( max(rank + chai, global, bang hội, 1) + cộng thêm, max-multiplier )
```

"Cộng thêm" là phần plugin khác gửi vào qua API (BoxPet dùng để cộng hệ số từ pet). Loại số nguyên như mining chỉ
nhận phần nguyên. Boost đang chạy hiện trên bossbar và lưu theo thời điểm hết hạn thật, restart không mất thời gian.

## Gem

Số dư Gem theo người chơi, lưu ở `gems.db` (SQLite) kèm sổ giao dịch (tự xoá bản ghi cũ hơn `gem.log-keep-days`).
Gem không phải economy của Vault; Vault vẫn là tiền chính của server. Admin cộng / trừ được cả người offline. Chuyển
Gem giữa người chơi (`/gem pay`) mặc định tắt, cấp quyền mới dùng được.

## Lệnh

| Lệnh | Quyền | Mô tả |
|---|---|---|
| `/booster` | `boxcore.use` | Hướng dẫn boost |
| `/gem` (`/gems`, `/ngoc`) | `boxcore.gem.use` | Xem số dư |
| `/gem top` | `boxcore.gem.use` | Bảng xếp hạng Gem |
| `/gem pay <người chơi> <số>` | `boxcore.gem.pay` | Chuyển Gem (mặc định không ai có) |
| `/boxcore give <người chơi> <loại> <hệ số> <thời gian> [số lượng] [clan]` | `boxcore.admin` | Tặng chai boost, thêm `clan` để tạo chai bang hội |
| `/boxcore global start <loại> <hệ số> <thời gian>` | `boxcore.admin` | Bật boost toàn server |
| `/boxcore global stop <loại>` | `boxcore.admin` | Tắt boost toàn server |
| `/boxcore check <người chơi>` | `boxcore.admin` | Xem từng nguồn boost của một người |
| `/boxcore gem give\|take\|set <người chơi> <số> [lý do] [-s]` | `boxcore.admin` | Sửa Gem; `-s` không báo cho người chơi |
| `/boxcore gem check <người chơi>` | `boxcore.admin` | Xem Gem của người khác |
| `/boxcore reload` | `boxcore.admin` | Đọc lại config và messages |

Lý do ghi vào sổ giao dịch, ví dụ phần thưởng quest: `boxcore gem give {player} 50 quest:mine-t3`.

## Placeholder

| Placeholder | Giá trị |
|---|---|
| `%boxcore_multiple_<loại>%` | Hệ số hiệu lực (`2`, `1.5`; dấu thập phân theo `multiplier-decimal-separator`) |
| `%boxcore_multiple_<loại>_raw%` | Như trên nhưng luôn dùng dấu chấm |
| `%boxcore_clan_<loại>%`, `%boxcore_clan_<loại>_remaining%` | Hệ số và thời gian còn lại của boost bang hội |
| `%boxcore_bonus_<loại>%` | Phần cộng thêm từ plugin khác (pet) |
| `%boxcore_gem%`, `%boxcore_gem_formatted%`, `%boxcore_gem_short%` | Số dư: `1250`, `1.250`, `1,2K` |
| `%boxcore_gem_name%` | Tên hiển thị của Gem |
| `%boxcore_gem_top_<n>_name%`, `%boxcore_gem_top_<n>_amount%` | Hạng `n` của bảng xếp hạng |

## Dữ liệu (`plugins/BoxCore/`)

- `global-boosts.yml`, `player-boosts.yml`, `clan-boosts.yml`: boost đang chạy. File hỏng được đổi tên thành
  `*.broken-<thời gian>` thay vì bị xoá.
- `gems.db`: sao lưu khi server tắt, hoặc chép cả `gems.db-wal` và `gems.db-shm`. File hỏng không bao giờ bị xoá;
  Gem tạm tắt còn boost vẫn chạy.

## Cho plugin khác

API nằm trong module `boxcore-api` (package `com.dex.boxcore.api`), chỉ dùng `compileOnly`, không shade. Hướng dẫn đầy
đủ ở [INTEGRATION.md](INTEGRATION.md). `examples/boxcore-test-plugin` là một plugin mẫu dùng API.

## Build

```
./gradlew build                  # compile + test
./gradlew publishToMavenLocal    # cài com.dex:boxcore-api vào ~/.m2 cho plugin khác
```

| File | Dùng cho |
|---|---|
| `boxcore-plugin/build/libs/BoxCore-<version>.jar` | Bỏ vào `plugins/` (đã chứa sẵn API) |
| `boxcore-api/build/libs/boxcore-api-<version>.jar` | Cho plugin khác compile |

Repo chỉ chứa mã nguồn, không có jar nào ngoài `gradle/wrapper/gradle-wrapper.jar` của Gradle.

Ghi chú nâng cấp giữa các bản: [CHANGELOG.md](CHANGELOG.md).
