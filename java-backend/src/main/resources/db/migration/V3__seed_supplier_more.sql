-- ============================================================
-- 补充演示种子数据（全部为 MOCK 数据）
-- 原因：V2 中"办公用品"只有 2 家，比价表太薄，演示效果差
-- ============================================================

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-011', '博文具业有限公司', '办公用品', '宁波', 'https://example.com/suppliers/sup-011', '林可', '138****0011', 'ISO9001,FSC认证', 4.70, 0, 'MOCK 数据：复印纸自有产线，支持整柜直发', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-012', '启明文具有限公司', '办公用品', '广州', 'https://example.com/suppliers/sup-012', '黄涛', '138****0012', 'ISO9001', 3.60, 0, 'MOCK 数据：中小批量灵活，起订量低', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-013', '新元办公服务有限公司', '办公用品', '苏州', 'https://example.com/suppliers/sup-013', '许晴', '138****0013', 'ISO9001,ISO45001', 4.50, 0, 'MOCK 数据：本地仓，次日达，价格中等', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-014', '远航贸易有限公司', '办公用品', '义乌', 'https://example.com/suppliers/sup-014', '钱伟', '138****0014', '无', 2.90, 1, 'MOCK 数据：以次充好投诉记录，列入黑名单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-015', '云启信息技术有限公司', 'IT设备', '杭州', 'https://example.com/suppliers/sup-015', '邓宇', '138****0015', 'ISO9001,ISO27001', 4.60, 0, 'MOCK 数据：国产化整机，支持 3 年质保', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-016', '鼎新电子设备有限公司', 'IT设备', '成都', 'https://example.com/suppliers/sup-016', '范琳', '138****0016', '3C认证', 3.80, 0, 'MOCK 数据：配件齐全，交期波动较大', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-017', '泰和劳保科技有限公司', '劳保用品', '无锡', 'https://example.com/suppliers/sup-017', '蒋楠', '138****0017', 'LA认证,ISO9001', 4.40, 0, 'MOCK 数据：安全鞋/防护服，支持定制印字', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-018', '永盛包装制品有限公司', '包装材料', '东莞', 'https://example.com/suppliers/sup-018', '罗敏', '138****0018', 'ISO14001,FSC认证', 4.20, 0, 'MOCK 数据：瓦楞纸箱，起订量 500 起', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
