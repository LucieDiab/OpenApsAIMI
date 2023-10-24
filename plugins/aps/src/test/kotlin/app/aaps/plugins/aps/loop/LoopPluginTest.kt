package app.aaps.plugins.aps.loop

import android.app.NotificationManager
import android.content.Context
import app.aaps.core.data.aps.ApsMode
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.constraints.ConstraintsChecker
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.receivers.ReceiverStatusStore
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.nssdk.interfaces.RunningConfiguration
import app.aaps.pump.virtual.VirtualPumpPlugin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.Mockito.`when`

class LoopPluginTest : TestBaseWithProfile() {

    @Mock lateinit var constraintChecker: ConstraintsChecker
    @Mock lateinit var commandQueue: CommandQueue
    @Mock lateinit var virtualPumpPlugin: VirtualPumpPlugin
    @Mock lateinit var receiverStatusStore: ReceiverStatusStore
    @Mock lateinit var notificationManager: NotificationManager
    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var uel: UserEntryLogger
    @Mock lateinit var runningConfiguration: RunningConfiguration
    @Mock lateinit var uiInteraction: UiInteraction

    private lateinit var loopPlugin: LoopPlugin

    @BeforeEach fun prepare() {

        loopPlugin = LoopPlugin(
            injector, aapsLogger, aapsSchedulers, rxBus, sp, config,
            constraintChecker, rh, profileFunction, context, commandQueue, activePlugin, virtualPumpPlugin, iobCobCalculator, processedTbrEbData, receiverStatusStore, fabricPrivacy, dateUtil, uel,
            persistenceLayer, runningConfiguration, uiInteraction, instantiator
        )
        `when`(activePlugin.activePump).thenReturn(virtualPumpPlugin)
        `when`(context.getSystemService(Context.NOTIFICATION_SERVICE)).thenReturn(notificationManager)
    }

    @Test
    fun testPluginInterface() {
        `when`(rh.gs(app.aaps.core.ui.R.string.loop)).thenReturn("Loop")
        `when`(rh.gs(app.aaps.plugins.aps.R.string.loop_shortname)).thenReturn("LOOP")
        `when`(sp.getString(app.aaps.core.utils.R.string.key_aps_mode, ApsMode.OPEN.name)).thenReturn(ApsMode.CLOSED.name)
        val pumpDescription = PumpDescription()
        `when`(virtualPumpPlugin.pumpDescription).thenReturn(pumpDescription)
        assertThat(loopPlugin.pluginDescription.fragmentClass).isEqualTo(LoopFragment::class.java.name)
        assertThat(loopPlugin.getType()).isEqualTo(PluginType.LOOP)
        assertThat(loopPlugin.name).isEqualTo("Loop")
        assertThat(loopPlugin.nameShort).isEqualTo("LOOP")
        assertThat(loopPlugin.hasFragment()).isTrue()
        assertThat(loopPlugin.showInList(PluginType.LOOP)).isTrue()
        assertThat(loopPlugin.preferencesId.toLong()).isEqualTo(app.aaps.plugins.aps.R.xml.pref_loop.toLong())

        // Plugin is disabled by default
        assertThat(loopPlugin.isEnabled()).isFalse()
        loopPlugin.setPluginEnabled(PluginType.LOOP, true)
        assertThat(loopPlugin.isEnabled()).isTrue()

        // No temp basal capable pump should disable plugin
        virtualPumpPlugin.pumpDescription.isTempBasalCapable = false
        assertThat(loopPlugin.isEnabled()).isFalse()
        virtualPumpPlugin.pumpDescription.isTempBasalCapable = true

        // Fragment is hidden by default
        assertThat(loopPlugin.isFragmentVisible()).isFalse()
        loopPlugin.setFragmentVisible(PluginType.LOOP, true)
        assertThat(loopPlugin.isFragmentVisible()).isTrue()
    }

    /* ***********  not working
    @Test
    public void eventTreatmentChangeShouldTriggerInvoke() {

        // Unregister tested plugin to prevent calling real invoke
        MainApp.bus().unregister(loopPlugin);

        class MockedLoopPlugin extends LoopPlugin {
            boolean invokeCalled = false;

            @Override
            public void invoke(String initiator, boolean allowNotification) {
                invokeCalled = true;
            }

        }

        MockedLoopPlugin mockedLoopPlugin = new MockedLoopPlugin();
        Treatment t = new Treatment();
        bus.post(new EventTreatmentChange(t));
        assertThat(mockedLoopPlugin.invokeCalled).isTrue();
    }
*/
}
