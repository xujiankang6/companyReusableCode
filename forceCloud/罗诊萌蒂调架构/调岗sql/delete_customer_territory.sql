--UPDATE customer_territory
SET is_deleted = TRUE,
 VERSION = VERSION + 1,
 update_time = FLOOR(EXTRACT(epoch FROM now()) * 1000) -- SELECT
-- 	COUNT(*)
-- FROM
-- 	customer_territory
WHERE
	is_deleted = FALSE
AND ID IN(
	SELECT DISTINCT
		ct. ID -- DISTINCT
		-- 	ct. ID "customer_territory_id",
		-- 	 	territory.external_id "TERRITORY_EXTERNAL_ID",
		-- 	 	ui.external_id "USER_EXTERNAL_ID",
		-- 	 	ui. NAME "USER_NAME",
		-- 	 	customer. NAME "CUSTOMER_NAME",
		-- 	 	customer.external_id "CUSTOMER_EXTERNAL_ID",
		-- 	 	parent.external_id "parent_external_id",
		-- 	 	customer.record_type "CUSTOMER_TYPE"
	FROM
		customer_territory ct
	LEFT OUTER JOIN territory ON ct.territory = territory."id"
	LEFT OUTER JOIN user_territory ut ON ut.territory = territory."id"
	LEFT OUTER JOIN user_info ui ON ut.user_info = ui. ID
	LEFT OUTER JOIN customer ON customer."id" = ct.customer
	LEFT OUTER JOIN customer parent ON parent. ID = customer.parent_id
	WHERE
		territory.ext ->> 'belonged_product_line' IN(
			'非癌痛',  -- --'ME癌痛','ME非癌痛','ME术后痛'
			'癌痛',
			'术后痛',
			'全产品'
		)
	AND ct.is_deleted = FALSE -- AND ui.external_id = '101FJ112'
	-- ORDER BY
	-- 	"parent_external_id"
)