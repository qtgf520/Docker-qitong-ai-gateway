import re
# 模拟截图里的 YAML 风格
text = '''function_name: gateway_status
params: {}'''
# 格式8 YAML 解析：提取 function_name: xxx
yaml_m = re.findall(r'(?:function_name|function|name)\s*[:：]\s*([a-zA-Z_][a-zA-Z0-9_]*)', text)
print('YAML函数提取:', yaml_m)
# 模拟旧逻辑会误配 params
import re2 as _  # noqa
m = re.findall(r'([a-zA-Z_]+):\s*', text)
print('误匹配候选:', m)
# 过滤无意义函数名
skip = {'params','parameters','arguments','function','function_name','name','value','output','result','results','resp','response'}
filtered = [x for x in m if x not in skip]
print('过滤后:', filtered)
print('OK')