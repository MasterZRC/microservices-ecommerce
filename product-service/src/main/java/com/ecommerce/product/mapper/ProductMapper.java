package com.ecommerce.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.product.entity.Product;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

	@Update("""
			UPDATE product
			SET stock = stock - #{quantity},
				sales = sales + #{quantity},
				update_time = NOW()
			WHERE id = #{productId}
			  AND stock >= #{quantity}
			""")
	int reduceStockAtomic(@Param("productId") Long productId, @Param("quantity") Integer quantity);

	@Update("""
			UPDATE product
			SET stock = stock + #{quantity},
				update_time = NOW()
			WHERE id = #{productId}
			""")
	int increaseStockAtomic(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}