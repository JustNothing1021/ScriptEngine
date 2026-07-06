package com.justnothing.engine.repl;

import com.justnothing.engine.lexer.Lexer;
import com.justnothing.engine.parser.CythavaParseException;
import com.justnothing.engine.parser.ParseContext;
import com.justnothing.engine.parser.Parser;
import com.justnothing.engine.preprocessor.Preprocessor;

import java.util.Arrays;

/**
 * 解析器性能基准测试（分阶段计时版）。
 * <p>
 * 分别测量 预处理 / 词法分析 / 语法分析 三个阶段的耗时，
 * 帮助定位性能瓶颈。
 */
public class ParseBenchmark {

    private static final int WARMUP_ROUNDS = 5;
    private static final int BENCH_ROUNDS = 50;

    public static void main(String[] args) {
        String source = MiniAppSource.SOURCE;
        int lineCount = (int) source.lines().count();
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║      Cythava Parser Benchmark v2.0       ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  源码: MiniApp (Text Adventure RPG)%n");
        System.out.printf("  规模: %d 行, %d 字符%n", lineCount, source.length());
        System.out.printf("  配置: 预热 %d 轮 + 测试 %d 轮%n", WARMUP_ROUNDS, BENCH_ROUNDS);
        System.out.println();

        // ── Warmup ──
        System.out.print("  [1/2] 预热中");
        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            runParseStaged(source);
            System.out.print(".");
        }
        System.out.println(" 完成");

        // ── Benchmark ──
        long[][] stages = new long[BENCH_ROUNDS][3]; // [0]=preprocess, [1]=lexer, [2]=parser
        long[] totalTimes = new long[BENCH_ROUNDS];
        System.out.print("  [2/2] 测试中");
        for (int i = 0; i < BENCH_ROUNDS; i++) {
            var result = runParseStaged(source);
            stages[i][0] = result[0];
            stages[i][1] = result[1];
            stages[i][2] = result[2];
            totalTimes[i] = result[0] + result[1] + result[2];
            System.out.print(".");
            if ((i + 1) % 10 == 0) System.out.print(" " + (i + 1));
        }
        System.out.println(" 完成");

        printResults(totalTimes, stages);
    }

    /**
     * 分阶段执行，返回 [预处理耗时, 词法耗时, 语法耗时]（纳秒）
     */
    private static long[] runParseStaged(String source) {
        try {
            ParseContext context = new ParseContext();
            context.setStrictMode(false);

            long t0 = System.nanoTime();
            String processed = new Preprocessor().process(source);
            long t1 = System.nanoTime();

            Lexer lexer = new Lexer(processed, "<benchmark>");
            var tokens = lexer.tokenize();
            long t2 = System.nanoTime();

            Parser parser = new Parser(tokens, context, "<benchmark>");
            parser.parse();
            long t3 = System.nanoTime();

            return new long[]{t1 - t0, t2 - t1, t3 - t2};
        } catch (CythavaParseException e) {
            throw new RuntimeException("解析失败: " + e.getMessage(), e);
        }
    }

    private static void printResults(long[] totals, long[][] stages) {
        Arrays.sort(totals);

        double avgTotalMs = Arrays.stream(totals).average().orElse(0) / 1_000_000.0;
        long avgPre = avgCol(stages, 0);
        long avgLex = avgCol(stages, 1);
        long avgPar = avgCol(stages, 2);

        System.out.println();
        System.out.println("┌──────────────────────────────────────┐");
        System.out.println("│         Benchmark 结果 (v2)          │");
        System.out.println("├──────────────────────────────────────┤");

        // 总览
        System.out.printf("│  总计:  最小 %6.2f ms  P50 %6.2f ms   │%n",
                totals[0] / 1e6, totals[totals.length / 2] / 1e6);
        System.out.printf("│         P90  %6.2f ms  平均 %6.2f ms   │%n",
                totals[(int)(totals.length * 0.9)] / 1e6, avgTotalMs);
        System.out.println("├──────────────────────────────────────┤");

        // 各阶段占比
        long sum = avgPre + avgLex + avgPar;
        System.out.println("│  分阶段耗时（平均）:                  │");
        printBar("预处理", avgPre, sum);
        printBar("词法分析", avgLex, sum);
        printBar("语法分析", avgPar, sum);
        System.out.println("├──────────────────────────────────────┤");
        System.out.printf("│  吞吐量: %.0f 行/秒                  │%n",
                (double) MiniAppSource.SOURCE.lines().count() / (avgTotalMs / 1000.0));
        System.out.println("└──────────────────────────────────────┘");
    }

    /** 计算二维数组某列的平均值 */
    private static long avgCol(long[][] data, int col) {
        long sum = 0;
        for (long[] row : data) sum += row[col];
        return sum / data.length;
    }

    /** 打印带比例条的阶段耗时行 */
    private static void printBar(String label, long ns, long totalNs) {
        double pct = totalNs > 0 ? ns * 100.0 / totalNs : 0;
        double ms = ns / 1_000_000.0;
        int barLen = Math.max(0, (int) Math.round(pct / 2.5)); // max ~40 chars
        char[] bar = new char[barLen];
        Arrays.fill(bar, '█');
        System.out.printf("│  %-8s %6.2f ms (%5.1f%%) %s│%n", label, ms, pct, new String(bar));
    }

    // ──────────────────────────────────────────────
    //  MiniApp 源码常量
    // ──────────────────────────────────────────────
    private static class MiniAppSource {
        static final String SOURCE = """
            #pragma typeCheck(false)
            println("========================================");
            println("   The Lost Kingdom - Text Adventure");
            println("========================================");
            println("");

            auto random = Random.new();
            auto scanner = Scanner.new(System.in);

            auto createPlayer = (name) -> {
                auto player = HashMap.new();
                player.put("name", name);
                player.put("hp", 100);
                player.put("maxHp", 100);
                player.put("attack", 15);
                player.put("defense", 5);
                player.put("gold", 50);
                player.put("level", 1);
                player.put("exp", 0);
                player.put("inventory", ArrayList.new());
                player.put("equippedWeapon", null);
                player.put("equippedArmor", null);

                player.put("takeDamage", (damage) -> {
                    auto currentHp = player.get("hp");
                    auto newHp = currentHp - damage;
                    if (newHp < 0) newHp = 0;
                    player.put("hp", newHp);
                    newHp;
                });

                player.put("heal", (amount) -> {
                    auto currentHp = player.get("hp");
                    auto maxHp = player.get("maxHp");
                    auto newHp = currentHp + amount;
                    if (newHp > maxHp) newHp = maxHp;
                    player.put("hp", newHp);
                    newHp - currentHp;
                });

                player.put("addExp", (amount) -> {
                    auto currentExp = player.get("exp");
                    auto newExp = currentExp + amount;
                    player.put("exp", newExp);
                    auto level = player.get("level");
                    auto expNeeded = level * 50;
                    if (newExp >= expNeeded) {
                        player.put("level", level + 1);
                        player.put("exp", newExp - expNeeded);
                        player.put("maxHp", player.get("maxHp") + 20);
                        player.put("attack", player.get("attack") + 5);
                        player.put("defense", player.get("defense") + 2);
                        player.put("hp", player.get("maxHp"));
                        println("  *** LEVEL UP! You are now level " + player.get("level") + "! ***");
                    }
                });

                player.put("addItem", (item) -> {
                    player.get("inventory").add(item);
                });

                player.put("showStatus", () -> {
                    println("");
                    println("=== " + player.get("name") + " ===");
                    println("Level: " + player.get("level") + "  EXP: " + player.get("exp") + "/" + (player.get("level") * 50));
                    println("HP: " + player.get("hp") + "/" + player.get("maxHp"));
                    println("ATK: " + player.get("attack") + "  DEF: " + player.get("defense"));
                    println("Gold: " + player.get("gold"));
                    auto weapon = player.get("equippedWeapon");
                    auto armor = player.get("equippedArmor");
                    if (weapon != null) println("Weapon: " + weapon.get("name"));
                    if (armor != null) println("Armor: " + armor.get("name"));
                });

                player;
            };

            auto createEnemy = (name, hp, attack, defense, gold, exp) -> {
                auto enemy = HashMap.new();
                enemy.put("name", name);
                enemy.put("hp", hp);
                enemy.put("maxHp", hp);
                enemy.put("attack", attack);
                enemy.put("defense", defense);
                enemy.put("gold", gold);
                enemy.put("exp", exp);
                enemy;
            };

            auto createItem = (name, type, value, price) -> {
                auto item = HashMap.new();
                item.put("name", name);
                item.put("type", type);
                item.put("value", value);
                item.put("price", price);
                item;
            };

            auto enemies = ArrayList.new();
            enemies.add(createEnemy("Goblin", 30, 10, 3, 15, 20));
            enemies.add(createEnemy("Wolf", 40, 12, 2, 20, 25));
            enemies.add(createEnemy("Orc", 60, 18, 8, 35, 40));
            enemies.add(createEnemy("Dark Knight", 80, 25, 15, 60, 60));
            enemies.add(createEnemy("Dragon", 150, 35, 20, 200, 150));

            auto shopItems = ArrayList.new();
            shopItems.add(createItem("Health Potion", "potion", 50, 30));
            shopItems.add(createItem("Iron Sword", "weapon", 10, 100));
            shopItems.add(createItem("Steel Sword", "weapon", 20, 250));
            shopItems.add(createItem("Iron Armor", "armor", 8, 120));
            shopItems.add(createItem("Steel Armor", "armor", 15, 300));

            auto calculateDamage = (attacker, defender) -> {
                auto baseDamage = attacker.get("attack");
                auto defense = defender.get("defense");
                auto damage = baseDamage - defense / 2;
                auto variance = random.nextInt(5) - 2;
                damage = damage + variance;
                if (damage < 1) damage = 1;
                damage;
            };

            auto battle = (player, enemy) -> {
                println("");
                println("========================================");
                println("  BATTLE: " + player.get("name") + " vs " + enemy.get("name"));
                println("========================================");

                auto playerHp = player.get("hp");
                auto enemyHp = enemy.get("hp");

                while (playerHp > 0 && enemyHp > 0) {
                    println("");
                    println(player.get("name") + " HP: " + playerHp + "/" + player.get("maxHp"));
                    println(enemy.get("name") + " HP: " + enemyHp + "/" + enemy.get("maxHp"));
                    println("");
                    println("1. Attack");
                    println("2. Use Potion");
                    println("3. Try to Escape");
                    print("Choose action: ");

                    auto choice = scanner.nextInt();

                    if (choice == 1) {
                        auto damage = calculateDamage(player, enemy);
                        enemyHp = enemyHp - damage;
                        if (enemyHp < 0) enemyHp = 0;
                        println("  You deal " + damage + " damage!");

                        if (enemyHp > 0) {
                            auto enemyDamage = calculateDamage(enemy, player);
                            playerHp = playerHp - enemyDamage;
                            if (playerHp < 0) playerHp = 0;
                            println("  " + enemy.get("name") + " deals " + enemyDamage + " damage!");
                        }
                    } else if (choice == 2) {
                        auto inventory = player.get("inventory");
                        auto hasPotion = false;
                        auto i = 0;
                        while (i < inventory.size()) {
                            auto item = inventory.get(i);
                            if (item.get("type").equals("potion")) {
                                hasPotion = true;
                                auto healAmount = player.get("heal").invoke(item.get("value"));
                                println("  You used " + item.get("name") + " and healed " + healAmount + " HP!");
                                inventory.remove(i);
                                break;
                            }
                            i = i + 1;
                        }
                        if (!hasPotion) {
                            println("  You have no potions!");
                        }

                        auto enemyDamage = calculateDamage(enemy, player);
                        playerHp = playerHp - enemyDamage;
                        println("  " + enemy.get("name") + " deals " + enemyDamage + " damage!");
                    } else if (choice == 3) {
                        if (random.nextInt(100) < 30) {
                            println("  You escaped successfully!");
                            return false;
                        } else {
                            println("  Failed to escape!");
                            auto enemyDamage = calculateDamage(enemy, player);
                            playerHp = playerHp - enemyDamage;
                            println("  " + enemy.get("name") + " deals " + enemyDamage + " damage!");
                        }
                    }
                }

                player.put("hp", playerHp);

                if (playerHp > 0) {
                    println("");
                    println("========================================");
                    println("  VICTORY!");
                    println("  Gained " + enemy.get("gold") + " gold and " + enemy.get("exp") + " EXP!");
                    println("========================================");
                    player.put("gold", player.get("gold") + enemy.get("gold"));
                    player.get("addExp").invoke(enemy.get("exp"));
                    true;
                } else {
                    println("");
                    println("========================================");
                    println("  DEFEAT... You have been slain!");
                    println("========================================");
                    false;
                }
            };

            auto showShop = (player) -> {
                println("");
                println("=== SHOP ===");
                println("Your gold: " + player.get("gold"));
                println("");
                auto i = 0;
                while (i < shopItems.size()) {
                    auto item = shopItems.get(i);
                    println((i + 1) + ". " + item.get("name") + " - " + item.get("price") + " gold");
                    i = i + 1;
                }
                println("0. Exit");
                print("Buy item: ");

                auto choice = scanner.nextInt();
                if (choice > 0 && choice <= shopItems.size()) {
                    auto item = shopItems.get(choice - 1);
                    if (player.get("gold") >= item.get("price")) {
                        player.put("gold", player.get("gold") - item.get("price"));
                        player.get("addItem").invoke(item);
                        println("  Purchased " + item.get("name") + "!");
                    } else {
                        println("  Not enough gold!");
                    }
                }
            };

            auto showInventory = (player) -> {
                println("");
                println("=== INVENTORY ===");
                auto inventory = player.get("inventory");
                if (inventory.size() == 0) {
                    println("Empty");
                    return;
                }

                auto i = 0;
                while (i < inventory.size()) {
                    auto item = inventory.get(i);
                    println((i + 1) + ". " + item.get("name") + " (" + item.get("type") + ")");
                    i = i + 1;
                }
                println("0. Exit");
                print("Equip/Use item: ");

                auto choice = scanner.nextInt();
                if (choice > 0 && choice <= inventory.size()) {
                    auto item = inventory.get(choice - 1);
                    auto type = item.get("type");

                    if (type.equals("weapon")) {
                        player.put("equippedWeapon", item);
                        player.put("attack", player.get("attack") + item.get("value"));
                        inventory.remove(choice - 1);
                        println("  Equipped " + item.get("name") + "!");
                    } else if (type.equals("armor")) {
                        player.put("equippedArmor", item);
                        player.put("defense", player.get("defense") + item.get("value"));
                        inventory.remove(choice - 1);
                        println("  Equipped " + item.get("name") + "!");
                    } else if (type.equals("potion")) {
                        auto healAmount = player.get("heal").invoke(item.get("value"));
                        inventory.remove(choice - 1);
                        println("  Used " + item.get("name") + " and healed " + healAmount + " HP!");
                    }
                }
            };

            auto explore = (player) -> {
                println("");
                println("You venture into the wilderness...");
                Thread.sleep(500);

                auto encounter = random.nextInt(100);

                if (encounter < 50) {
                    auto enemyIndex = random.nextInt(enemies.size());
                    auto enemy = enemies.get(enemyIndex);
                    println("  A wild " + enemy.get("name") + " appears!");
                    battle(player, enemy);
                } else if (encounter < 70) {
                    auto goldFound = random.nextInt(30) + 10;
                    println("  You found a treasure chest with " + goldFound + " gold!");
                    player.put("gold", player.get("gold") + goldFound);
                } else if (encounter < 85) {
                    auto potion = createItem("Health Potion", "potion", 50, 30);
                    player.get("addItem").invoke(potion);
                    println("  You found a Health Potion!");
                } else {
                    println("  Nothing happens... The area is peaceful.");
                }
            };

            println("Welcome, brave adventurer!");
            print("What is your name? ");
            auto playerName = scanner.next();

            auto player = createPlayer(playerName);

            println("");
            println("Greetings, " + playerName + "! Your adventure begins...");
            println("You find yourself in a small village. The kingdom needs heroes!");

            auto playing = true;

            while (playing && player.get("hp") > 0) {
                println("");
                println("========================================");
                println("          THE LOST KINGDOM");
                println("========================================");
                println("1. Explore");
                println("2. Visit Shop");
                println("3. Check Inventory");
                println("4. View Status");
                println("5. Rest at Inn (10 gold)");
                println("0. Quit Game");
                print("Choose action: ");

                auto choice = scanner.nextInt();

                if (choice == 1) {
                    explore(player);
                } else if (choice == 2) {
                    showShop(player);
                } else if (choice == 3) {
                    showInventory(player);
                } else if (choice == 4) {
                    player.get("showStatus").invoke();
                } else if (choice == 5) {
                    if (player.get("gold") >= 10) {
                        player.put("gold", player.get("gold") - 10);
                        player.put("hp", player.get("maxHp"));
                        println("  You rest at the inn and fully recover!");
                    } else {
                        println("  Not enough gold!");
                    }
                } else if (choice == 0) {
                    playing = false;
                    println("");
                    println("Thanks for playing!");
                    player.get("showStatus").invoke();
                }
            }

            if (player.get("hp") <= 0) {
                println("");
                println("========================================");
                println("  GAME OVER");
                println("  Final Score: Level " + player.get("level") + ", " + player.get("gold") + " gold");
                println("========================================");
            }
            """;
    }
}
