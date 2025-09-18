package com.milite.battle;

import lombok.*;
import java.util.*;
import java.util.stream.Collectors;

import com.milite.battle.abilities.*;
import com.milite.battle.artifacts.*;
import com.milite.constants.BattleConstants;
import com.milite.dto.BattleResultDto;
import com.milite.dto.PlayerDto;
import com.milite.dto.SkillDto;
import com.milite.util.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BattleSession {
	private int sessionID;
	private PlayerDto player;
	private ArrayList<BattleMonsterUnit> enemy;
	private boolean isFinished;
	private int currentTurn = 1;
	private List<BattleLogEntry> battleLog = new ArrayList<>();
	private List<BattleUnit> actionOrder = new ArrayList<>();
	private int currentActionIndex = 0;

	public BattleResultDto battleTurn(BattleUnit attacker, List<BattleUnit> allUnits, Integer targetIndex,
			SkillDto skill, BattleContext context) {
		// 플레이어가 하는 공격처리
		if (!attacker.getUnitType().equals("Player")) {
			return new BattleResultDto("잘못된 대상 접근", 0, 0, false, false, null);
		}

		PlayerDto player = (PlayerDto) attacker;

		// 턴 시작 시의 아티팩트
		player.executeArtifactsOnTurnStart(context);

		List<BattleUnit> validTargets = getValidTargets(allUnits, attacker);
		BattleState battleState = new BattleState();

		String actor = attacker.getName();
		String actorJosa = KoreanUtil.getJosa(actor, "이 ", "가 "); // 이(가) 같은 표현 없어도 되도록 하는 함수
		int attackerAtk = getAttackPower(attacker);
		// 공격자의 공격력 받아오기

		int totalHitTimes= skill.getHit_time() + getPlayerExtraHitTimes(player);
		
		for (int hitCount = 0; hitCount < totalHitTimes; hitCount++) {
			executeAttackByType(skill.getHit_target(), validTargets, targetIndex, attacker, skill, attackerAtk, actor,
					actorJosa, battleState, context);
		}

		// 턴 종료 시의 아티팩트
		player.executeArtifactsOnTurnEnd(context);

		boolean isDefeated = checkBattleEnd(allUnits, attacker);
		String fullDetails = String.join("\n", battleState.getDetails());

		List<BattleLogEntry> currentBattleLog = new ArrayList<>();
		currentBattleLog.add(new BattleLogEntry(actor, "attack", fullDetails, 0));

		currentBattleLog.addAll(context.getLogs());

		return new BattleResultDto("플레이어 공격 완료", battleState.getTotalDamage(), 0, battleState.isAnyHit(), isDefeated,
				currentBattleLog);
	}

	public BattleResultDto battleTurn(BattleUnit attacker, List<BattleUnit> allUnits, Integer targetIndex,
			SkillDto skill) {
		BattleContext context = new BattleContext(this, this.currentTurn);
		return battleTurn(attacker, allUnits, targetIndex, skill, context);
	}

	public BattleResultDto battleTurn(BattleUnit attacker, List<BattleUnit> allUnits, BattleContext context) {
		// 몬스터가 하는 공격 처리, 현재 턴 전체 흐름 관리가 processMonsterAttack에 위임되어있음. 추후 수정 필요(todo)
		BattleUnit player = getAlivePlayer(allUnits);
		if (player == null) {
			return new BattleResultDto("공격 대상 없음", 0, 0, false, true, new ArrayList<>());
		}
		return processMonsterAttack(attacker, player, context);
	}

	private void executeAttackByType(String targetType, List<BattleUnit> validTargets, Integer targetIndex,
			BattleUnit attacker, SkillDto skill, int attackerAtk, String actor, String actorJosa,
			BattleState battleState, BattleContext context) {
		switch (targetType) {
		case "Pick":
			if (targetIndex < validTargets.size()) {
				BattleUnit target = validTargets.get(targetIndex);
				if (target.isAlive()) {
					executeAttackOnTarget(attacker, target, skill, attackerAtk, actor, actorJosa, battleState, context);
				}
			}
			break;
		case "All":
			validTargets.stream().filter(BattleUnit::isAlive).forEach(target -> executeAttackOnTarget(attacker, target,
					skill, attackerAtk, actor, actorJosa, battleState, context));
			break;
		case "Random":
			List<BattleUnit> aliveTargets = validTargets.stream().filter(BattleUnit::isAlive)
					.collect(Collectors.toList());

			if (!aliveTargets.isEmpty()) {
				int randomIndex = CommonUtil.Dice(aliveTargets.size());
				BattleUnit target = aliveTargets.get(randomIndex);
				executeAttackOnTarget(attacker, target, skill, attackerAtk, actor, actorJosa, battleState, context);
			}
			break;
		}
	}

	private void executeAttackOnTarget(BattleUnit attacker, BattleUnit target, SkillDto skill, int attackerAtk,
			String actor, String actorJosa, BattleState battleState, BattleContext context) {
		int targetLuck = getTargetLuck(target);
		boolean isHit = isAttacked(targetLuck);

		boolean isPlayer = attacker.getUnitType().equals("Player");

		if (isPlayer) {
			PlayerDto player = (PlayerDto) attacker;
			player.executeArtifactsOnAttack(target, context);
		}

		if (isHit) {
			int baseDamage = calcAtk(attackerAtk, skill);

			int artifactBonusDamage = 0;
			if (isPlayer) {
				PlayerDto player = (PlayerDto) attacker;
				artifactBonusDamage = getArtifactBonusDamage(player, skill);
			}

			double elementMultiplier;
			if (isPlayer) {
				PlayerDto player = (PlayerDto) attacker;
				elementMultiplier = calculateFinalElementMultiplier(player, skill.getElement(),
						getTargetElement(target));
			} else {
				elementMultiplier = calculateElementMultiplier(skill.getElement(), getTargetElement(target));
			}

			int finalDamage = (int) ((baseDamage + artifactBonusDamage) * elementMultiplier);

			String damageMessage = buildDamageMessage(actor, actorJosa, target.getName(), finalDamage,
					elementMultiplier);
			battleState.addDetail(damageMessage);

			boolean wasAliveBeforeHit = target.isAlive();

			context.damageUnit(target, finalDamage);
			battleState.addDamage(finalDamage);
			battleState.setAnyHit(true);

			if (isPlayer) {
				PlayerDto player = (PlayerDto) attacker;
				player.executeArtifactsOnHit(target, finalDamage, context);
			}

			if (wasAliveBeforeHit && target instanceof BattleMonsterUnit) {
				BattleMonsterUnit monster = (BattleMonsterUnit) target;
				monster.executeOnDefensePerHit(attacker, finalDamage, context);
				// context.executeDelayedActions();
			}

			if (!target.isAlive()) {
				String defeatMessage = target.getName() + KoreanUtil.getJosa(target.getName(), "이 ", "가 ") + "쓰러졌습니다.";
				battleState.addDetail(defeatMessage);
			}
		} else {
			String missMessage = actor + actorJosa + target.getName() + "에게 공격을 가했으나 회피했습니다.";
			battleState.addDetail(missMessage);
		}
	}

	private BattleResultDto processMonsterAttack(BattleUnit attacker, BattleUnit target, BattleContext context) {
		// 턴 흐름 관리를 추후에 BattleTurn으로 이관해야함
		BattleMonsterUnit monster = (BattleMonsterUnit) attacker;
		String actor = attacker.getName();
		String actorJosa = KoreanUtil.getJosa(actor, "이 ", "가 ");
		BattleState battleState = new BattleState();

		int attackTimes = getMonsterAttackTimes(monster);

		for (int i = 0; i < attackTimes && target.isAlive(); i++) {
			int targetLuck = getTargetLuck(target);
			boolean isHit = isAttacked(targetLuck);

			monster.executeOnAttack(target, context);

			if (isHit) {
				int damage = calcMonsterAttack(monster);

				String damageMessage;
				if (isThreeMultipleTurn()
						&& ("ThreeChance".equals(monster.getSpecial()) || "ThreeStack".equals(monster.getSpecial()))) {
					damageMessage = actor + actorJosa + target.getName() + "에게 강화된 공격으로 " + damage + "의 피해를 입혔습니다!";
				} else {

					damageMessage = actor + actorJosa + target.getName() + "에게 " + damage + "의 피해를 입혔습니다.";
				}

				battleState.addDetail(damageMessage);

				monster.executeOnHit(target, damage, context);

				context.damageUnit(target, damage);
				battleState.addDamage(damage);
				battleState.setAnyHit(true);

				if (!target.isAlive()) {
					String defeatMessage = target.getName() + KoreanUtil.getJosa(target.getName(), "이 ", "가 ")
							+ "쓰러졌습니다.";
					battleState.addDetail(defeatMessage);
					break;
				}
			} else {
				String missMessage = actor + actorJosa + target.getName() + "에게 공격했으나 회피했습니다.";
				battleState.addDetail(missMessage);
			}
		}

		/*
		 * if (battlestats.isAnyHit() && battleState.getTotalDamage()> 0){ if(target
		 * instanceof PlayerDto){ 플레이어의 피격 시 효과 넣을 곳 } }
		 */
		boolean isPlayerDefeated = !target.isAlive();
		String fullDetails = String.join("\n", battleState.getDetails());

		List<BattleLogEntry> currentBattleLog = new ArrayList<>();
		currentBattleLog.add(new BattleLogEntry(actor, "attack", fullDetails, 0));

		currentBattleLog.addAll(context.getLogs());

		return new BattleResultDto("몬스터 공격 완료", battleState.getTotalDamage(), target.getHp(), battleState.isAnyHit(),
				isPlayerDefeated, currentBattleLog);
	}

	private double calculateElementMultiplier(String attackElement, String targetElement) {
		return BattleConstants.getElementMultiplier(attackElement, targetElement);
	}

	private double calculateFinalElementMultiplier(PlayerDto player, String attackElement, String targetElement) {
		double baseMultiplier = calculateElementMultiplier(attackElement, targetElement);
		double totalBonus = 0.0;

		for (PlayerArtifact artifact : player.getArtifacts()) {
			if (artifact instanceof ElementStoneArtifact) {
				ElementStoneArtifact stone = (ElementStoneArtifact) artifact;
				if (stone.hasElementAdvantage(baseMultiplier)) {
					totalBonus += stone.getElementAdvantageBonus();
				}
			}

			if (artifact instanceof MagicianAmuletArtifact) {
				MagicianAmuletArtifact amulet = (MagicianAmuletArtifact) artifact;
				if (amulet.hasElementDisadvantage(baseMultiplier)) {
					totalBonus += amulet.getElementDisadvantageBonus();
				}
			}

			if (artifact instanceof ForbiddenScrollArtifact) {
				ForbiddenScrollArtifact scroll = (ForbiddenScrollArtifact) artifact;
				if (scroll.hasElementAdvantage(baseMultiplier)) {
					totalBonus += scroll.getElementAdvantageBonus();
				}
				if (scroll.hasElementDisadvantage(baseMultiplier)) {
					totalBonus -= scroll.getElementDisadvantageBonus();
				}
			}
		}

		return baseMultiplier + totalBonus;
	}

	private String getTargetElement(BattleUnit target) {
		if (target.getUnitType().equals("Monster")) {
			return ((BattleMonsterUnit) target).getElement();
		}
		return "None";
	}

	private int getArtifactBonusDamage(PlayerDto player, SkillDto skill) {
		int totalBonusDamage = 0;
		String skillElement = skill.getElement();

		for (PlayerArtifact artifact : player.getArtifacts()) {
			if (artifact instanceof FighterGuildMedalArtifact) {
				FighterGuildMedalArtifact medal = (FighterGuildMedalArtifact) artifact;
				totalBonusDamage += medal.calculateDamageBonus(skillElement);
			} else if (artifact instanceof BurningLavaStoneArtifact) {
				BurningLavaStoneArtifact stone = (BurningLavaStoneArtifact) artifact;
				totalBonusDamage += stone.calculateDamageBonus(skillElement);
			} else if (artifact instanceof BlueTridentArtifact) {
				BlueTridentArtifact trident = (BlueTridentArtifact) artifact;
				totalBonusDamage += trident.calculateDamageBonus(skillElement);
			} else if (artifact instanceof DruidBeltArtifact) {
				DruidBeltArtifact belt = (DruidBeltArtifact) artifact;
				totalBonusDamage += belt.calculateDamageBonus(skillElement);
			} // 추가로 데미지 추가형 아티팩트를 넣을 때 여기에 넣을 것
		}

		return totalBonusDamage;
	}

	private String buildDamageMessage(String actor, String actorJosa, String targetName, int damage,
			double multiplier) {

		String baseMessage = actor + actorJosa + targetName + "에게 " + damage + "의 피해를 입혔습니다.";

		return baseMessage;
	}

	private List<BattleUnit> getValidTargets(List<BattleUnit> allUnits, BattleUnit attacker) {
		return allUnits.stream().filter(unit -> unit.isAlive() && !unit.equals(attacker)).collect(Collectors.toList());
	}

	private BattleUnit getAlivePlayer(List<BattleUnit> allUnits) {
		return allUnits.stream().filter(unit -> unit.isAlive() && unit.getUnitType().equals("Player")).findFirst()
				.orElse(null);
	}

	private int getAttackPower(BattleUnit unit) {
		// 플레이어의 공격력 불러오기
		if (unit.getUnitType().equals("Player")) {
			return ((PlayerDto) unit).getAtk();
		}
		return 0;
	}

	private int calcMonsterAttack(BattleMonsterUnit monster) {
		// 몬스터의 공격력 불러오기
		int min_atk = monster.getMin_atk();
		int max_atk = monster.getMax_atk();
		int baseDamage = (int) (Math.random() * (max_atk - min_atk + 1)) + min_atk;

		double multiplier = 1.0;

		if ("ThreeChance".equals(monster.getSpecial())) {
			multiplier = ThreeChanceAbility.getDamageMultiplier(monster, this.currentTurn);
		} else if ("ThreeStack".equals(monster.getSpecial())) {
			multiplier = ThreeStackAbility.getDamageMultiplier(monster, this.currentTurn);
		} else if ("FormChange".equals(monster.getSpecial())) {
			multiplier = FormChangeAbility.getAttackMultiplier(monster, this.currentTurn);
		} else if ("ModeSwitch".equals(monster.getSpecial())) {
			multiplier = ModeSwitchAbility.getAttackMultiplier(monster, this.currentTurn);
		}

		int finalDamage = (int) Math.round(baseDamage * multiplier);

		return finalDamage;
	}

	private int getPlayerExtraHitTimes(PlayerDto player) {
		int extra = 0;
	
		for (PlayerArtifact artifact : player.getArtifacts()) {
			if (artifact instanceof ShadowDeviceArtifact) {
				ShadowDeviceArtifact device = (ShadowDeviceArtifact) artifact;
				extra += device.getHitTimeBonus();
			}
		}
		return extra;
	}
	
	private int getMonsterAttackTimes(BattleMonsterUnit monster) {
		// 몬스터의 공격횟수 불러오기(이 부분은 나중을 생각해서 미리 작성함)
		String special = monster.getSpecial();
		if (special != null) {
			switch (special) {
			case "DoubleAttack":
				return 2;
			case "TripleAttack":
				return 3;
			default:
				return 1;
			}
		}
		return 1;
	}

	private int getTargetLuck(BattleUnit target) {
		// 행운 수치 불러오기
		if (target.getUnitType().equals("Player")) {
			return ((PlayerDto) target).getLuck();
		} else if (target.getUnitType().equals("Monster")) {
			return ((BattleMonsterUnit) target).getLuck();
		}
		return 0;
	}

	private boolean checkBattleEnd(List<BattleUnit> allUnits, BattleUnit attacker) {
		// 죽은 대상이 있는지 없는지 파악하여 전투가 끝났는지 확인
		if (attacker.getUnitType().equals("Player")) {
			return allUnits.stream().filter(unit -> unit.getUnitType().equals("Monster"))
					.noneMatch(BattleUnit::isAlive);
		} else {
			return allUnits.stream().filter(unit -> unit.getUnitType().equals("Player")).noneMatch(BattleUnit::isAlive);
		}
	}

	private boolean isAttacked(int luck) {
		// 명중 여부 확인하기
		return isAttacked(luck, null, null);
	}

	private boolean isAttacked(int luck, BattleUnit attacker, BattleUnit target) {
		if (attacker != null && attacker.getUnitType().equals("Player")) {
			PlayerDto player = (PlayerDto) attacker;
			for (PlayerArtifact artifact : player.getArtifacts()) {
				if (artifact instanceof OverloadCrystalArtifact) {
					OverloadCrystalArtifact crystal = (OverloadCrystalArtifact) artifact;
					if (crystal.canUse()) {
						crystal.useEffect();
						return true;
					}
				}
			}
		}

		int n = CommonUtil.Dice(BattleConstants.getBaseDodgeRoll());
		int dodgeChance = n * BattleConstants.getDodgeMultiplier() + luck;

		if (attacker != null && attacker.getUnitType().equals("Player")) {
			PlayerDto player = (PlayerDto) attacker;

			if (BlindAbility.isBlind(attacker)) {
				dodgeChance += BattleConstants.getBlindDodgeBonus();
			}

			int hitRateBonus = calculatePlayerHitRateBonus(player);

			dodgeChance -= hitRateBonus;
		}

		if (target != null && target.getUnitType().equals("Player")) {
			PlayerDto player = (PlayerDto) target;

			int DodgeRateBonus = calculatePlayerDodgeRateBonus(player);

			dodgeChance += DodgeRateBonus;
		}

		int roll = (int) (Math.random() * BattleConstants.getBattleHitRollMax()) + 1;
		return roll > dodgeChance;
	}

	private int calculatePlayerHitRateBonus(PlayerDto player) {
		int totalHitBonus = 0;

		for (PlayerArtifact artifact : player.getArtifacts()) {
			if (artifact instanceof BlurryLensArtifact) {
				BlurryLensArtifact lens = (BlurryLensArtifact) artifact;
				totalHitBonus += lens.getHitBonus();
			}
		}
		return totalHitBonus;
	}

	private int calculatePlayerDodgeRateBonus(PlayerDto player) {
		int totalDodgeBonus = 0;

		for (PlayerArtifact artifact : player.getArtifacts()) {
			if (artifact instanceof GrayCloakArtifact) {
				GrayCloakArtifact cloak = (GrayCloakArtifact) artifact;
				totalDodgeBonus += cloak.getDodgeBonus();
			}
		}
		return totalDodgeBonus;
	}

	private int calcAtk(int atk, SkillDto skill) {
		// 플레이어가 실제로 가할 데미지 계산
		int calc = 0;

		int dmg_range = (int) (Math.random() * (skill.getMax_damage() - skill.getMin_damage())) + skill.getMin_damage();
		calc = calc + (int) (dmg_range + Math.floor(atk / BattleConstants.getBattleAtkDivisor()));

		return calc;
	}

	private boolean isThreeMultipleTurn() {
		return this.currentTurn > 0 && this.currentTurn % 3 == 0;
	}

	@Data
	private static class BattleState {
		private List<String> details = new ArrayList<>();
		private int totalDamage = 0;
		private boolean anyHit = false;

		public BattleState() {
			this.details = new ArrayList<>();
			this.totalDamage = 0;
			this.anyHit = false;
		}

		public void addDetail(String detail) {
			this.details.add(detail);
		}

		public void addDamage(int damage) {
			this.totalDamage += damage;
		}
	}
}