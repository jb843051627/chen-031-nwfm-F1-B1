package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TNwfmSettleRow;
import com.fc.v2.service.ITNwfmSettleRowService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 清算赔付条目 Controller
 *
 * @author fuce
 * @date 2026-09-12
 */
@Api(value = "清算赔付条目")
@Controller
@RequestMapping("/NwfmSettleRowController")
public class NwfmSettleRowController extends BaseController {

    private final String prefix = "admin/nwfmSettleRow";

    @Autowired
    private ITNwfmSettleRowService nwfmSettleRowService;

    @ApiOperation(value = "分页跳转", notes = "分页跳转")
    @GetMapping("/view")
    @RequiresPermissions("nwfm:nwfmSettleRow:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "清算赔付条目集合查询", action = "list")
    @ApiOperation(value = "分页查询", notes = "分页查询")
    @GetMapping("/list")
    @RequiresPermissions("nwfm:nwfmSettleRow:list")
    @ResponseBody
    public ResultTable list(TNwfmSettleRow record) {
        QueryWrapper<TNwfmSettleRow> queryWrapper = new QueryWrapper<TNwfmSettleRow>();
        startPage();
        com.github.pagehelper.PageInfo<TNwfmSettleRow> page =
                new com.github.pagehelper.PageInfo<TNwfmSettleRow>(nwfmSettleRowService.selectTNwfmSettleRowList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "清算赔付条目新增", action = "add")
    @ApiOperation(value = "新增", notes = "新增")
    @PostMapping("/add")
    @RequiresPermissions("nwfm:nwfmSettleRow:add")
    @ResponseBody
    public AjaxResult add(TNwfmSettleRow record) {
        return toAjax(nwfmSettleRowService.insertTNwfmSettleRow(record));
    }

    @Log(title = "清算赔付条目修改", action = "edit")
    @ApiOperation(value = "修改保存", notes = "修改保存")
    @PostMapping("/edit")
    @RequiresPermissions("nwfm:nwfmSettleRow:edit")
    @ResponseBody
    public AjaxResult editSave(TNwfmSettleRow record) {
        return toAjax(nwfmSettleRowService.updateTNwfmSettleRow(record));
    }

    @Log(title = "清算赔付条目删除", action = "remove")
    @ApiOperation(value = "删除", notes = "删除")
    @DeleteMapping("/remove")
    @RequiresPermissions("nwfm:nwfmSettleRow:remove")
    @ResponseBody
    public AjaxResult remove(String ids) {
        return toAjax(nwfmSettleRowService.deleteTNwfmSettleRowByIds(ids));
    }
}
