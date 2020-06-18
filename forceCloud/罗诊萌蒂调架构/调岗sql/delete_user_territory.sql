-- UPDATE user_territory
SET is_deleted = TRUE,
 VERSION = VERSION + 1,
 update_time = FLOOR(EXTRACT(epoch FROM now()) * 1000) 
-- SELECT
-- 	COUNT(*)
-- FROM
-- 	user_territory
WHERE
	is_deleted = FALSE
AND ID IN(
	SELECT DISTINCT
		ut. ID -- 			 	,territory.external_id "territory_external_id"
		--			 	,territory.territory_type_code
		-- 			 	,ui. NAME
		-- 			 	,ui.external_id "user_external_id"
		-- 			 	,territory.ext ->> 'belonged_product_line' "product_line"
	FROM
		user_territory ut
	LEFT OUTER JOIN territory ON ut.territory = territory. ID
	LEFT OUTER JOIN user_info ui ON ut.user_info = ui. ID
	WHERE
		territory.ext ->> 'belonged_product_line' IN(
-- 			'非癌痛',
-- 			'癌痛',
-- 			'术后痛',
-- 			'全产品'
		)
	AND ut.is_deleted = FALSE -- 		AND ui.external_id = '101FJ112'
	-- 		ORDER BY
	-- 			territory.territory_type_code,
	-- 			"territory_external_id"
)