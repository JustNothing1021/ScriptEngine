package com.justnothing.engine.repl;

import com.justnothing.engine.ScriptRunner;

public class MiniApp {

    public static void main(String[] args) {
        System.out.println("=== Mini App: Text Adventure RPG (engine_new) ===\n");
        runTextAdventure();
    }

    private static void runTextAdventure() {
        try {
            ScriptRunner runner = new ScriptRunner();
            runner.addImport("java.util.ArrayList");
            runner.addImport("java.util.HashMap");
            runner.addImport("java.util.Random");
            runner.addImport("java.util.Scanner");

            String source = """
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

            runner.execute(source);

            System.out.println("\n✓ Mini App completed\n");
        } catch (Exception e) {
            System.err.println("✗ Mini App failed: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }
}
