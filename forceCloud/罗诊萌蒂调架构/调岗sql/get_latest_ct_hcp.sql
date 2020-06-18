SELECT DISTINCT
	-- count(*)
	hcp.external_id "customer",
	-- 	ct.territory,
	territory.external_id "territory",
	'TRUE' "is_active",
	ct.ext ->> 'batch_code' "batch_code"
FROM
	"T7299637928496136".customer hco
LEFT JOIN "T7299637928496136".customer_territory ct ON hco. ID = ct.customer
LEFT JOIN "T7299637928496136".customer hcp ON hcp.parent_id = hco. ID
LEFT JOIN "T7299637928496136".territory ON territory. ID = ct.territory
WHERE
	hcp.is_active = TRUE
AND hcp.is_deleted = FALSE
AND hco.is_active = TRUE
AND hcp.record_type = 'hcp' -- govlk/soclk/dislk
AND hco.record_type = 'hco' -- gov/soc/dis
AND ct.is_deleted = FALSE
AND ct.ext ->> 'batch_code' = 'FSG_20200423'





-- 无效的判定条件（or）：
-- 1.对应的Customer已经无效；
-- -- 2.对应的Territory已经无效；
-- 
-- SELECT * FROM customer_product c WHERE c.customer in (SELECT id FROM customer t WHERE t.is_active = FALSE)
-- 
-- SELECT * FROM customer_product cp WHERE belong_territory in (SELECT id FROM territory t WHERE t.is_active = FALSE)
