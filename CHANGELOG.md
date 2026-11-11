# Changelog

Ghi chú khi nâng cấp. Mọi bản chỉ cần thay jar; config đang dùng không bị ghi đè.

## 1.6.0 (cộng thêm từ pet)

- Chỉ thay jar `BoxCore-1.6.0.jar`, không cần sửa config. `messages.yml` có thêm 2 dòng `check.bonus` /
  `check.bonus-none` (thiếu thì dùng mặc định trong jar).
- Plugin khác (BoxPet) có thể cộng thêm một hệ số P lên trên nguồn đang thắng:
  `hiệu lực = min(max(rank + chai, global, bang hội, x1) + P, tối đa)`. Mine là số nguyên nên chỉ nhận phần nguyên.
- `/boxcore check <người chơi>` hiện thêm dòng "Cộng thêm (pet)"; placeholder mới `%boxcore_bonus_<type>%`.

## 1.5.0 (boost bang hội, chai mới)

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

## 1.4.0 (bội số lẻ)

- `config.yml` đang dùng **không** bị ghi đè. Thêm tay `decimal: true` vào `types.quest` rồi `/boxcore reload`; thiếu khóa này thì loại đó chỉ nhận số nguyên như cũ. `mining` giữ `decimal: false` (CataMines chỉ dùng hệ số nguyên). Tuỳ chọn: `multiplier-decimal-separator: '.'`.
- `messages.yml` có khóa mới `error.multiplier-decimal`; thiếu thì dùng bản mặc định (một dòng cảnh báo khi khởi động).
- Chai và file boost cũ dùng tiếp được; chai nguyên của 1.3.0 và 1.4.0 vẫn gộp chồng.
- Hạ cấp về 1.3.0: chai lẻ (x1.5) không dùng được, boost lẻ đang chạy bị bỏ khi nạp file.
- Tắt `decimal` của một loại khi đang có boost lẻ chạy: hệ số hiệu lực bỏ phần lẻ (x2.5 thành x2), chai lẻ còn trong túi không dùng được (không bị mất).
