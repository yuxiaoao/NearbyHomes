# NearbyHomes

查询 HuskHomes 里某个位置附近的家。**直接读数据库**，所以能看到私人家（不依赖游戏内权限），适合服主/管理排查"身边是谁的地盘"。

## 安装

1. 把 `NearbyHomes-1.0.0.jar` 放进 `plugins/`
2. 重启服务器（或 `/reload` 不推荐）
3. 首次启动会生成 `plugins/NearbyHomes/config.yml`

依赖：Paper/Spigot 1.20+，Java 17+，装了 HuskHomes（软依赖，没装也能启动但查询会报错）。

## 命令

```
/nearbyhomes                  # 100 格内（默认半径），当前世界
/nearbyhomes 200              # 200 格内
/nearbyhomes 100 world        # 指定世界
/nh 100                       # 别名
```

权限：`nearbyhomes.use`（默认仅 OP）

## 输出示例

```
100 格内找到 2 个家：
58.3 格 base (Alex) [50, 70, 30] world [公开]
98.5 格 edge_case (Steve) [90, 64, -40] world
```

## 配置

`plugins/NearbyHomes/config.yml`

```yaml
default-radius: 100     # 默认半径
max-radius: 500         # 上限，防止误查太远
max-results: 30         # 最多显示条数

database:
  type: auto            # auto 时自动读 plugins/HuskHomes/config.yml
  server: ''            # 跨服时只看某个子服
```

`type: auto` 时会读取 HuskHomes 的 `config.yml`，支持 SQLite / MySQL / MariaDB / H2。

## 实现要点

- **表名/列名运行时探测**：通过 JDBC metadata 找表，兼容 HuskHomes 不同版本的命名与前缀
- **支持两种表结构**
  - 关联表（HuskHomes 4.11+ 默认）：`home → saved_position → position`，三表 JOIN
  - 单表（旧版）：坐标直接存在家表上
- **距离按水平面算**（X/Z），和地图一致；不同维度坐标不混算
- **异步查询**：数据库操作跑在异步线程，结果回到主线程输出
- **只读**：不写库，不会破坏 HuskHomes 数据

## 数据库结构（供排查）

HuskHomes 4.11 的表大致是：

```
huskhomes_home_data           uuid, saved_position_id, owner_uuid, public
huskhomes_saved_position_data id, position_id, name, description, tags
huskhomes_position_data       id, x, y, z, yaw, pitch, world_name, server_name
huskhomes_player_data         uuid, username, ...
```

实际表名取决于 `table_prefix` 配置，插件会自动识别。

## 注意

- 建议在**低峰或关闭服务器**时查询，避免和 HuskHomes 的写入抢锁
- 读的是别人的私人坐标，**注意服规和隐私**
- 如果世界名对不上，会提示数据库里实际存在的世界名

## 自己构建

需要 JDK 17+ 和 Maven：

```bash
mvn -DskipTests package
# 产物：target/NearbyHomes-1.0.0.jar
```

SQLite 和 MySQL 驱动已经打包进 jar（约 18 MB），服务器端不需要额外装驱动。
