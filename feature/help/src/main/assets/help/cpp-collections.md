# 实践 4：字符串与容器

目标：使用标准库管理一组成绩，避免手工分配数组。把 `native_lab` 的源文件列表恢复为 `main.cpp`，替换源码：

```cpp
#include <algorithm>
#include <iostream>
#include <numeric>
#include <string>
#include <vector>

int main() {
    const std::string name = "Tina";
    const std::vector<int> grades{60, 80, 100};
    if (grades.empty()) { std::cerr << "no grades\n"; return 1; }
    const bool valid = std::all_of(grades.begin(), grades.end(), [](int grade) {
        return grade >= 0 && grade <= 100;
    });
    if (!valid) { std::cerr << "invalid grade\n"; return 1; }
    const int sum = std::accumulate(grades.begin(), grades.end(), 0);
    const double average = static_cast<double>(sum) / grades.size();
    std::cout << name << ": count=" << grades.size() << " average=" << average << '\n';
    return 0;
}
```

预期：`Tina: count=3 average=80`。`vector` 自动管理连续存储；`begin/end` 表示范围；lambda 表达一个成绩是否合法。先把和转成 `double` 再除法，才能保留小数。

## 边界与遍历

- 改为 `{60, 81}`：平均值应为 `70.5`。
- 改为空 `{}`：返回错误，不执行除以零。
- 加入 `101`：返回 `invalid grade`。
- 用 `for (int grade : grades)` 输出每个分数；想修改分数时才使用 `int&`。

访问已知位置可使用 `grades.at(index)`，越界会抛异常；`operator[]` 不检查边界，循环条件必须正确。不要保存一个元素引用后再 `push_back` 并继续使用该引用，扩容可能使它失效。

`std::string::size()` 对 UTF-8 文本返回字节数，不能当作中文字符个数。请保存为 UTF-8，并用整段输出检查中文，不随意按字节切片。

## 自己完成

添加最高分和及格人数。先列出只有一项、全不及格、含无效值三组预期；空容器仍应优先拒绝。使用 `max_element` 和 `count_if` 时解释返回值类型。

## 继续学习

- [对象与资源生命周期](cpp-raii.md)
