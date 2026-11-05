# BoxCore

Plugin lõi của server BoxPvP (Paper 1.21.1, Java 21). Phiên bản 1.4.0: **boost** (bội số theo loại: số nguyên, riêng loại `decimal: true` như `quest` được lẻ 1 chữ số, ví dụ x1.5) và tiền tệ **GEM**.

Công thức (mỗi người chơi, mỗi loại; nguồn không có tính là 0):

```
hiệu lực = min( max(rank + chai, global, 1), max-multiplier )
```

Rank cộng dồn với chai cá nhân; mỗi loại chỉ có **1 chai đang chạy** (chai cao hơn thay chai cũ); global không cộng dồn.

Đặc tả: [BOXCORE_SPEC.md](BOXCORE_SPEC.md) + [BOXCORE_SPEC_UPDATE_01.md](BOXCORE_SPEC_UPDATE_01.md) + [BOXCORE_SPEC_UPDATE_03.md](BOXCORE_SPEC_UPDATE_03.md) (Gem; bản 1.2.0 bị bỏ qua) + [BOXCORE_SPEC_UPDATE_04.md](BOXCORE_SPEC_UPDATE_04.md) (bội số lẻ cho quest). Tài liệu cho plugin khác tích hợp: [INTEGRATION.md](INTEGRATION.md).

## Build

```
./gradlew build                 # compile + unit test
./gradlew publishToMavenLocal   # cài com.dex:boxcore-api:1.2.0 vào ~/.m2
```

Kết quả:

| File | Dùng cho |
|---|---|
| `boxcore-plugin/build/libs/BoxCore-1.6.0.jar` | bỏ vào `plugins/` của server (đã chứa API, không relocate) |
| `boxcore-api/build/libs/boxcore-api-1.3.0.jar` | cho plugin khác compile (`provided` / `compileOnly`); 1.3.0 chỉ thêm API boost bang hội, plugin build với API cũ vẫn chạy |

Plugin test tích hợp (chỉ compile với API qua `mavenLocal()`): `examples/boxcore-test-plugin` — chạy `../../gradlew build` trong thư mục đó, lệnh trong game `/boxcoretest [player]`.

## Lệnh

| Lệnh | Quyền |
|---|---|
| `/booster` | `boxcore.use` (mặc định true) |
| `/boxcore give <player> <type> <multiplier> <duration> [amount] [clan]` | `boxcore.admin` (`clan` = chai bang hội) |
| `/boxcore global start <type> <multiplier> <duration>` | `boxcore.admin` |
| `/boxcore global stop <type>` | `boxcore.admin` |
| `/boxcore reload` | `boxcore.admin` |
| `/boxcore check <player>` | `boxcore.admin` |
| `/boxcore gem give\|take\|set <player> <amount> [reason] [-s]` | `boxcore.admin` (chạy được từ console, cả người chơi offline) |
| `/boxcore gem check <player>` | `boxcore.admin` |
| `/gem` (bí danh `/gems`, `/ngoc`) | `boxcore.gem.use` (mặc định true) |
| `/gem top` | `boxcore.gem.use` |
| `/gem pay <player> <amount>` | `boxcore.gem.pay` (mặc định **false**, cấp bằng LuckPerms) |

Quyền rank: `boxcore.boost.<type>.<n>` (ví dụ `boxcore.boost.mining.5`). Loại `decimal: true` nhận thêm dạng `<nguyên>_<1 chữ số>`: `boxcore.boost.quest.1_5` = x1.5 (không dùng dấu chấm được).

`<multiplier>` của `give` / `global start`: loại `mining` là số nguyên từ 2; loại `quest` (`decimal: true`) từ 1.1, tối đa 1 chữ số thập phân (`1.5` hoặc `1,5`).

`-s`: không báo cho người nhận. Ví dụ thưởng quest bằng lệnh console: `boxcore gem give {player} 50 quest:mine-t3`.

Placeholder:

| Placeholder | Giá trị |
|---|---|
| `%boxcore_multiple_<type>%` | bội số boost hiệu lực (`2`, `1.5`; dấu thập phân theo `multiplier-decimal-separator`) |
| `%boxcore_multiple_<type>_raw%` | như trên nhưng luôn dùng dấu chấm, cho plugin cần đọc số |
| `%boxcore_clan_<type>%` | hệ số boost bang hội của người chơi (`1` nếu không có) |
| `%boxcore_clan_<type>_remaining%` | thời gian còn lại của boost bang hội (rỗng nếu không có) |
| `%boxcore_gem%` | số dư Gem, số thô (`1250`) |
| `%boxcore_gem_formatted%` | `1.250` |
| `%boxcore_gem_short%` | `1,2K`, `3,4M` |
| `%boxcore_gem_name%` | tên hiển thị của Gem |
| `%boxcore_gem_top_<n>_name%`, `%boxcore_gem_top_<n>_amount%` | hạng `n` của bảng xếp hạng |

## File dữ liệu (`plugins/BoxCore/`)

- `config.yml`, `messages.yml` — cấu hình và toàn bộ text (tiếng Việt, small caps bật mặc định). Có khóa `config-version: 2`; file của bản 1.0.0 (không có khóa này) được tự động sao lưu thành `*.1.0.0.bak` và thay bằng mặc định mới khi khởi động.
- `global-boosts.yml`, `player-boosts.yml`, `clan-boosts.yml` — boost đang chạy, lưu theo thời điểm hết hạn tuyệt đối (giờ thực). File hỏng sẽ được đổi tên thành `*.broken-<timestamp>`.
- `gems.db` (SQLite) — số dư Gem (`gem_balances`) và sổ giao dịch (`gem_transactions`, xóa bản ghi cũ hơn `gem.log-keep-days`). Sao lưu khi server tắt, hoặc chép kèm `gems.db-wal` và `gems.db-shm`. File hỏng **không** bao giờ bị xóa: Gem tạm tắt, boost vẫn chạy. Server nâng cấp từ 1.1.0 không cần sửa `config.yml`: thiếu mục `gem` thì dùng mặc định.

## Nâng cấp lên 1.4.0 (bội số lẻ)

- `config.yml` đang dùng **không** bị ghi đè. Thêm tay `decimal: true` vào `types.quest` rồi `/boxcore reload`; thiếu khóa này thì loại đó chỉ nhận số nguyên như cũ. `mining` giữ `decimal: false` (CataMines chỉ dùng hệ số nguyên). Tuỳ chọn: `multiplier-decimal-separator: '.'`.
- `messages.yml` có khóa mới `error.multiplier-decimal`; thiếu thì dùng bản mặc định (một dòng cảnh báo khi khởi động).
- Chai và file boost cũ dùng tiếp được; chai nguyên của 1.3.0 và 1.4.0 vẫn gộp chồng.
- Hạ cấp về 1.3.0: chai lẻ (x1.5) không dùng được, boost lẻ đang chạy bị bỏ khi nạp file.
- Tắt `decimal` của một loại khi đang có boost lẻ chạy: hệ số hiệu lực bỏ phần lẻ (x2.5 thành x2), chai lẻ còn trong túi không dùng được (không bị mất).

## Nâng cấp lên 1.6.0 (cộng thêm từ pet)

- Chỉ thay jar `BoxCore-1.6.0.jar`, không cần sửa config. `messages.yml` có thêm 2 dòng `check.bonus` /
  `check.bonus-none` (thiếu thì dùng mặc định trong jar).
- Plugin khác (BoxPet) có thể cộng thêm một hệ số P lên trên nguồn đang thắng:
  `hiệu lực = min(max(rank + chai, global, bang hội, x1) + P, tối đa)`. Mine là số nguyên nên chỉ nhận phần nguyên.
- `/boxcore check <người chơi>` hiện thêm dòng "Cộng thêm (pet)"; placeholder mới `%boxcore_bonus_<type>%`.

## Nâng cấp lên 1.5.0 (boost bang hội, chai mới)

- **Chai mới:** mining là chai mật ong (`HONEY_BOTTLE`), quest là chai nước (`POTION`), đổi được bằng
  `types.<loại>.bottle-material`. `config.yml` cũ không có khoá này vẫn dùng đúng 2 chất liệu trên. Chai mới stack
  được 64. Chai kinh nghiệm cũ vẫn dùng được. Chai không uống, không chế tạo, không bỏ vào lò thuốc được.
- **Chữ trên chai** không còn small caps hay in đậm. `messages.yml` cũ: tên / lore chai và `/booster` còn đúng chữ
  mặc định 1.4.0 thì tự dùng chữ mới; chữ đã sửa tay được giữ. Tin nhắn mới thiếu trong file thì lấy bản mặc định (1
  dòng thông tin khi khởi động).
- **Chai cá nhân:** cùng hệ số với chai đang chạy thì **cộng thêm thời gian** (tối đa `max-duration`, bị cắt thì hỏi
  xác nhận); hệ số thấp hơn thì **từ chối, giữ chai**; cao hơn vẫn thay thế sau khi xác nhận.
- **Boost bang hội** (cần BoxClans 1.3.0 trở lên): `/boxcore give <người> <loại> <hệ số> <thời gian> [số lượng] clan`
  tạo chai bang hội, chỉ Minh Chủ / Phó Bang dùng được. Hệ số = cao nhất giữa (rank + chai), global, bang hội. Bossbar
  riêng, `/boxcore check` có dòng "Bang hội", file `clan-boosts.yml`. Không có BoxClans: chai bang hội báo lỗi, không mất.
- **Tin nhắn gem:** số gem và chữ "Gem" cùng màu tím của gem (`<gem>`, `gem.color` trong `config.yml`), ví dụ
  "Bạn nhận được 1.250 Gem". `messages.yml` cũ còn đúng chữ mặc định thì tự dùng chữ mới; dòng đã sửa tay được giữ.
- Placeholder mới: `%boxcore_clan_<loại>%` (hệ số bang hội, `1` nếu không có), `%boxcore_clan_<loại>_remaining%`.
- API `boxcore-api-1.3.0`: chỉ thêm (mục 12 của `INTEGRATION.md`); plugin build với API cũ chạy bình thường.
