package com.milite.mapper;

import org.apache.ibatis.annotations.Param;

import com.milite.dto.PlayerDto;

public interface CharacterStatusMapper {
	PlayerDto getPlayerInfo(@Param("PlayerID") String playerId); // XML의 #{PlayerID}와 일치

	int updateStatus(PlayerDto p);

	void addSkillToPlayer(@Param("playerId") String playerId, @Param("skillId") int skillId);

	void addArtifactToPlayer(@Param("playerId") String playerId, @Param("artifactId") int artifactId);

	public int replacePhoenixFeathers(String PlayerID);

	void addSkillToPlayerByString(@Param("playerId") String playerId, @Param("skillId") String skillId);

	void updatePlayerSkills(@Param("playerId") String playerId, @Param("usingSkills") String usingSkills,
			@Param("ownedSkills") String ownedSkills);

	int applyAllDeltas(@Param("playerId") String playerId, @Param("dPMaxHp") int dPMaxHp, @Param("dPHp") int dPHp,
			@Param("dPAtk") int dPAtk, @Param("dPLuck") int dPLuck, @Param("dMMaxHp") int dMMaxHp,
			@Param("dMHp") int dMHp, @Param("dMAtk") int dMAtk);
}