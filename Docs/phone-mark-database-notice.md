# PhoneNumber 本地号码标记库说明

本项目随 APK 集成的 `app/src/main/res/raw/marked_2016.db` 来自
[xdtianyu/PhoneNumber](https://github.com/xdtianyu/PhoneNumber)，固定在
`legacy` 分支提交 `11c2edd3c20d6500ad3073c85a6c2ea14e6489fd`，上游文件为
`phone-number/src/main/res/raw/marked.db`。该文件最后一次在上游仓库修改于
2016-03-11，包含 25,547 条记录；`type` 由上游代码映射为骚扰电话、诈骗、推销、
广告推销、房产中介、快递送餐、诈骗电话、外卖、中介和快递。

上游仓库声明 Apache-2.0 许可；相应许可文本随本应用资源打包于
`app/src/main/res/raw/phone_number_apache_license.txt`。上游没有说明号码记录的
具体采集来源或独立数据授权情况，因此保留来源和版本信息，不将它描述为当前或
官方标记结果。

此库只作为离线提示显示在来电浮窗、号码查询和查询历史中。它不会自动拦截号码，
也不改变 SpamBlocker 原有规则；号码可能已停用、重新分配或标签过时，请勿仅凭此
旧库做高影响判断。
