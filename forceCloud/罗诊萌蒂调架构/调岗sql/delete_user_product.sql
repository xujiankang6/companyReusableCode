UPDATE user_product 
SET 
is_deleted = 't' ,
VERSION = VERSION + 1,
 update_time = FLOOR(EXTRACT(epoch FROM now()) * 1000) 
where  id in (
SELECT
user_product.id
	--count(user_info.ID)
FROM
	user_info
INNER JOIN user_product
ON user_info.id=user_product.user_info
WHERE
user_info.profile IN (
		SELECT
			ID
		FROM
			profile
		WHERE
			api_name IN (
				'ap_rep_profile',
				'ap_dsm_profile',
				'ap_rsm_profile',
				'ap_bus_profile'
			)
		AND is_deleted = FALSE -- 注意这里根据简档更新  ('') 
	)
AND "enable" = 't'
AND user_product.is_deleted=FALSE
and user_info.is_deleted=false )
 

 

================================================
-- 针对不同产品线

-- UPDATE user_product
-- SET is_deleted = TRUE,
--  VERSION = VERSION + 1,
--  update_time = FLOOR(EXTRACT(epoch FROM now()) * 1000) 
SELECT
	*
FROM
	user_product
WHERE
	is_deleted = FALSE
AND ID IN(
	SELECT
		up. ID -- 			,ui.external_id,
		-- 			ui. NAME,
		-- 			product. NAME,
		-- 			product.external_id,
		-- 			product."level"
	FROM
		user_product up
	LEFT OUTER JOIN user_info ui ON up.user_info = ui. ID
	LEFT OUTER JOIN product ON up.product = product. ID
	LEFT OUTER JOIN user_territory ut on up.user_info = ut.user_info
	LEFT OUTER JOIN territory t on ut.territory = ut.id
	WHERE
		up.is_deleted = FALSE
		and ext->>'belonged_product_line' like '' -- 所属产品线
-- 	AND(
-- 		product.external_id LIKE 'NCP%' -- 非癌痛
-- 		-- OR product.external_id LIKE 'CP%'  --癌痛
-- 		OR product.external_id LIKE 'POP%' -- 术后痛
-- 	) -- 		AND ui.external_id = '101FJ112'
)
