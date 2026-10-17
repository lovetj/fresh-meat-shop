package com.freshmeat.controller;

import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.common.PageResult;
import com.freshmeat.common.Result;
import com.freshmeat.dto.ProductDTO;
import com.freshmeat.dto.ProductQueryDTO;
import com.freshmeat.entity.Product;
import com.freshmeat.service.ProductService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Api(tags = "鲜肉商品管理模块")
@RestController
@RequestMapping("/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 顾客端：商品列表 (分页、分类筛选、关键词搜索)
     */
    @ApiOperation(value = "商品列表 (顾客端)", notes = "支持按分类ID、商品名称模糊搜索并分页")
    @GetMapping("/list")
    public Result<PageResult<Product>> list(ProductQueryDTO queryDTO) {
        return Result.success(productService.pageCustomerProducts(queryDTO));
    }

    /**
     * 顾客端与管理端：商品详情
     */
    @ApiOperation(value = "获取商品详情", notes = "返回商品图片、规格切法选项、库存、价格与卖点")
    @GetMapping("/detail/{id}")
    public Result<Product> detail(@PathVariable String id) {
        return Result.success(productService.getProductDetail(id));
    }

    /**
     * 管理端：商品列表 (含全部与下架)
     */
    @ApiOperation(value = "商品管理列表 (掌柜端)", notes = "包含在售与已下架的全部商品列表")
    @RequireAdmin
    @GetMapping("/admin/list")
    public Result<PageResult<Product>> adminList(ProductQueryDTO queryDTO) {
        return Result.success(productService.pageAdminProducts(queryDTO));
    }

    /**
     * 管理端：保存或修改商品
     */
    @ApiOperation(value = "保存/修改商品", notes = "传入id则修改，不传id则新增商品")
    @RequireAdmin
    @PostMapping("/admin/save")
    public Result<Boolean> saveOrUpdate(@RequestBody @Validated ProductDTO dto) {
        boolean ok = productService.saveOrUpdateProduct(dto);
        return Result.success("保存商品成功", ok);
    }

    /**
     * 管理端：快捷更新上下架状态
     */
    @ApiOperation(value = "快捷切换上下架", notes = "参数：id, isOnSale(1上架, 0下架)")
    @RequireAdmin
    @PostMapping("/admin/status")
    public Result<Boolean> updateStatus(@RequestBody Map<String, Object> params) {
        String id = params.get("id").toString();
        Integer isOnSale = Integer.valueOf(params.get("isOnSale").toString());
        boolean ok = productService.updateSaleStatus(id, isOnSale);
        return Result.success(isOnSale == 1 ? "已上架" : "已下架", ok);
    }

    /**
     * 管理端：快捷更新库存
     */
    @ApiOperation(value = "快捷修改库存", notes = "参数：id, stock")
    @RequireAdmin
    @PostMapping("/admin/stock")
    public Result<Boolean> updateStock(@RequestBody Map<String, Object> params) {
        String id = params.get("id").toString();
        Integer stock = Integer.valueOf(params.get("stock").toString());
        boolean ok = productService.updateStock(id, stock);
        return Result.success("库存更新成功", ok);
    }

    /**
     * 管理端：删除商品
     */
    @ApiOperation(value = "删除商品", notes = "根据ID彻底删除商品")
    @RequireAdmin
    @DeleteMapping("/admin/{id}")
    public Result<Boolean> delete(@PathVariable String id) {
        boolean ok = productService.deleteProduct(id);
        return Result.success("删除成功", ok);
    }
}
