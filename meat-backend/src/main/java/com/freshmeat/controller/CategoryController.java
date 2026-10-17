package com.freshmeat.controller;

import com.freshmeat.annotation.RequireAdmin;
import com.freshmeat.common.Result;
import com.freshmeat.dto.CategoryDTO;
import com.freshmeat.entity.Category;
import com.freshmeat.service.CategoryService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Api(tags = "鲜肉分类模块")
@RestController
@RequestMapping("/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /**
     * 顾客端：获取指定店铺的启用分类列表
     */
    @ApiOperation(value = "获取有效分类列表 (顾客端)", notes = "根据店铺获取前端已启用的鲜肉分类")
    @GetMapping("/list")
    public Result<List<Category>> list(
            @ApiParam("店铺ID (选填，默认主店铺)") @RequestParam(value = "shopId", required = false) String shopId) {
        return Result.success(categoryService.listActiveCategories(shopId));
    }

    /**
     * 管理端：获取指定店铺的所有分类
     */
    @ApiOperation(value = "获取店铺分类列表 (掌柜端)", notes = "按店铺获取启用和停用状态的全部肉品分类")
    @RequireAdmin
    @GetMapping("/admin/list")
    public Result<List<Category>> adminList(
            @ApiParam("店铺ID (选填，若不填则获取当前管理员管辖的所有店铺分类)") @RequestParam(value = "shopId", required = false) String shopId) {
        return Result.success(categoryService.listAdminCategories(shopId));
    }

    /**
     * 管理端：新增或修改分类
     */
    @ApiOperation(value = "保存/修改分类", notes = "传入id为修改，不传id为新增")
    @RequireAdmin
    @PostMapping("/admin/save")
    public Result<Boolean> saveOrUpdate(@RequestBody @Validated CategoryDTO dto) {
        boolean ok = categoryService.saveOrUpdateCategory(dto);
        return Result.success("保存分类成功", ok);
    }

    /**
     * 管理端：删除分类
     */
    @ApiOperation(value = "删除分类", notes = "根据ID删除肉品分类")
    @RequireAdmin
    @DeleteMapping("/admin/{id}")
    public Result<Boolean> delete(@PathVariable String id) {
        boolean ok = categoryService.deleteCategory(id);
        return Result.success("删除成功", ok);
    }
}
