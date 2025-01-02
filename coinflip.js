// Updated coinflip.js fix sync issues between discord and the minecraft balance 
const axios = require('axios');
const moment = require('moment'); // Ensure consistent timestamp handling

const apiUrl = ''; // URL to notify Minecraft for balance sync

const CACHE_EXPIRATION = 5000; // 5 seconds
const playerCache = new Map();

async function updatePlayerBalance(mongoClient, username, adjustment) {
    const collection = mongoClient.db('PlayersSynced').collection('SyncedPlayers');

    // Check cache
    let player = playerCache.get(username);
    if (!player || Date.now() - player.cachedAt > CACHE_EXPIRATION) {
        player = await collection.findOne({ username });
        if (!player) return { success: false, message: 'Player not found.' };
        player.cachedAt = Date.now();
        playerCache.set(username, player);
    }

    const newBalance = (player.balance || 0) + adjustment;

    if (newBalance < 0) return { success: false, message: 'Insufficient balance.' };

    const currentTimestamp = new Date().toISOString();
    const lastUpdated = player.lastUpdated || '1970-01-01T00:00:00.000Z';

    if (moment(currentTimestamp).isBefore(moment(lastUpdated)) && adjustment < 0) {
        return { success: false, message: 'Your balance update conflicts with a newer transaction.' };
    }

    // Update MongoDB and cache
    const result = await collection.updateOne(
        { username, lastUpdated: { $lt: currentTimestamp } },
        { $set: { balance: newBalance, lastUpdated: currentTimestamp } },
        { upsert: true }
    );

    if (result.modifiedCount > 0) {
        playerCache.set(username, { balance: newBalance, lastUpdated: currentTimestamp, cachedAt: Date.now() });
        await notifyMinecraftForSync(username);
        return { success: true, balance: newBalance };
    }

    return { success: false, message: 'Failed to update balance due to conflict.' };
}

async function notifyMinecraftForSync(username) {
    try {
        const response = await axios.post(apiUrl, { username });
        console.log(`Minecraft sync triggered for ${username}:`, response.data);
    } catch (error) {
        console.error(`Failed to notify Minecraft for sync:`, error.message);
    }
}

module.exports = {
    data: {
        name: 'coinflip',
        description: 'Play a 50/50 game using your Minecraft balance!',
        options: [{ type: 4, name: 'bet', description: 'Bet amount', required: true }],
    },

    async execute(interaction, mongoClient) {
        const betAmount = interaction.options.getInteger('bet');
        const discordId = interaction.user.id;

        try {
            const collection = mongoClient.db('PlayersSynced').collection('SyncedPlayers');
            const playerData = await collection.findOne({ discordId });

            if (!playerData) {
                return interaction.reply({ content: 'Link your Discord account with Minecraft first.', ephemeral: true });
            }

            await interaction.deferReply({ ephemeral: true });

            const result = await updatePlayerBalance(mongoClient, playerData.username, -betAmount);
            if (!result.success) {
                return interaction.editReply({ content: result.message });
            }

            const outcome = Math.random() < 0.5 ? 'win' : 'lose';
            const winnings = outcome === 'win' ? betAmount * 2 : 0;

            if (winnings > 0) {
                await updatePlayerBalance(mongoClient, playerData.username, winnings);
            }

            return interaction.editReply({
                content: outcome === 'win'
                    ? `🎉 You won! +${winnings}`
                    : `😞 You lost ${betAmount}. Better luck next time.`,
            });
        } catch (err) {
            console.error('Coinflip error:', err.message);
            return interaction.editReply({ content: 'An error occurred. Try again.' });
        }
    },
};
