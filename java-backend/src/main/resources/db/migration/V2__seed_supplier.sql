-- ============================================================
-- 演示用种子数据（供应商主数据，全部为 MOCK 数据，域名 example.com 不可真实解析）
-- 说明：mock 供应商用于断网演示；真实模式下由 Python 侧检索公开页面回填
-- ============================================================

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-001', '华东办公用品有限公司', '办公用品', '上海', 'https://example.com/suppliers/sup-001', '王敏', '138****0001', 'ISO9001,ISO14001', 4.60, 0, 'MOCK 数据：A4 纸、文具长期供应', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-002', '恒信文具有限公司', '办公用品', '杭州', 'https://example.com/suppliers/sup-002', '李强', '138****0002', 'ISO9001', 4.20, 0, 'MOCK 数据：低价走量，交期偏长', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-003', '联创数码科技有限公司', 'IT设备', '深圳', 'https://example.com/suppliers/sup-003', '陈曦', '138****0003', 'ISO9001,3C认证', 4.80, 0, 'MOCK 数据：笔记本/显示器一级代理', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-004', '北方计算设备有限公司', 'IT设备', '北京', 'https://example.com/suppliers/sup-004', '赵磊', '138****0004', 'ISO9001', 3.90, 0, 'MOCK 数据：政企渠道，账期 60 天', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-005', '安泰劳保用品厂', '劳保用品', '青岛', 'https://example.com/suppliers/sup-005', '孙丽', '138****0005', 'LA认证,ISO9001', 4.30, 0, 'MOCK 数据：安全帽/劳保鞋', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-006', '金盾防护装备有限公司', '劳保用品', '苏州', 'https://example.com/suppliers/sup-006', '周涛', '138****0006', 'LA认证', 4.10, 0, 'MOCK 数据：防护手套/口罩', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-007', '精工五金制造有限公司', '五金工具', '东莞', 'https://example.com/suppliers/sup-007', '吴刚', '138****0007', 'ISO9001', 4.00, 0, 'MOCK 数据：标准件/手工具', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-008', '永固工具贸易有限公司', '五金工具', '宁波', 'https://example.com/suppliers/sup-008', '郑海', '138****0008', '无', 2.40, 1, 'MOCK 数据：失信被执行人记录，列入黑名单', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-009', '绿源包装材料有限公司', '包装材料', '佛山', 'https://example.com/suppliers/sup-009', '何静', '138****0009', 'ISO14001', 4.40, 0, 'MOCK 数据：纸箱/缓冲材料，支持定制', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO supplier (code, name, category, region, website, contact_name, contact_phone, qualifications, rating, blacklisted, note, created_at, updated_at)
VALUES ('SUP-010', '宏达化工原料有限公司', '化工原料', '淄博', 'https://example.com/suppliers/sup-010', '马超', '138****0010', '危化品经营许可证', 3.70, 0, 'MOCK 数据：需提供 MSDS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
