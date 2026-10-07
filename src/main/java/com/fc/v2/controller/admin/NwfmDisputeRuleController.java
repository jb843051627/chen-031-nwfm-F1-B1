package com.fc.v2.controller.admin;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.common.base.BaseController;
import com.fc.v2.common.domain.AjaxResult;
import com.fc.v2.common.domain.ResultTable;
import com.fc.v2.common.log.Log;
import com.fc.v2.model.auto.TNwfmDisputeRule;
import com.fc.v2.service.ITNwfmDisputeRuleService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.*;

/**
 * 计量争议调解判定规则 Controller（state-machine 形状：流转入口）
 *
 * @author fuce
 * @date 2026-09-14
 */
@Api(value = "计量争议调解判定规则")
@Controller
@RequestMapping("/nwfmDisputeRule")
public class NwfmDisputeRuleController extends BaseController {

    private final String prefix = "admin/nwfmDisputeRule";

    @Autowired
    private ITNwfmDisputeRuleService nwfmDisputeRuleService;

    @ApiOperation(value = "流转台账跳转", notes = "流转台账跳转")
    @GetMapping("/view")
    @RequiresPermissions("nwfmDisputeRule:view")
    public String view(ModelMap model) {
        return prefix + "/list";
    }

    @Log(title = "计量争议调解判定规则流转台账", action = "list")
    @ApiOperation(value = "流转台账", notes = "流转台账")
    @GetMapping("/list")
    @RequiresPermissions("nwfmDisputeRule:list")
    @ResponseBody
    public ResultTable list(TNwfmDisputeRule record) {
        QueryWrapper<TNwfmDisputeRule> queryWrapper = new QueryWrapper<TNwfmDisputeRule>();
        startPage();
        com.github.pagehelper.PageInfo<TNwfmDisputeRule> page =
                new com.github.pagehelper.PageInfo<TNwfmDisputeRule>(nwfmDisputeRuleService.selectTNwfmDisputeRuleList(queryWrapper));
        return pageTable(page.getList(), page.getTotal());
    }

    @Log(title = "计量争议调解判定规则推进", action = "advance")
    @ApiOperation(value = "推进一档", notes = "推进一档")
    @PostMapping("/advance")
    @RequiresPermissions("nwfmDisputeRule:advance")
    @ResponseBody
    public AjaxResult advance(Long id, String remark) {
        return toAjax(nwfmDisputeRuleService.advance(id, remark) != null ? 1 : 0);
    }

    @Log(title = "计量争议调解判定规则回退", action = "rollback")
    @ApiOperation(value = "回退一档", notes = "回退一档")
    @PostMapping("/rollback")
    @RequiresPermissions("nwfmDisputeRule:rollback")
    @ResponseBody
    public AjaxResult rollback(Long id, String remark) {
        return toAjax(nwfmDisputeRuleService.rollback(id, remark) != null ? 1 : 0);
    }
}
