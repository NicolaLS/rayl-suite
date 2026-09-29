package xyz.lilsus.raylsuite.feature.paymenthub

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import xyz.lilsus.raylsuite.core.model.LightningAddress
import xyz.lilsus.raylsuite.feature.paymenthub.host.PaymentHubController

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetHubViewModelTest {
    @Test
    fun galleryContactCreationVariantValidationAndSuccessfulUseDriveComputedWidgets() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        vm.openGallery()
        vm.selectDefinition("local.contacts")
        vm.selectVariant("row")
        vm.configureSelected()
        vm.addContact("Alice", "alice@example.com")
        advanceUntilIdle()
        val alice = repo.hub.value.contacts.single()
        val bob = repo.saveContact(assertNotNull(LightningAddress.parse("bob@example.com")), "Bob")
        advanceUntilIdle()
        vm.toggleContact(bob.id)
        vm.selectVariant("single")
        assertEquals(listOf(alice.id), vm.state.value.editor?.contactIds)
        vm.selectVariant("row")
        // A new widget's size starts with the suggested contacts.
        assertEquals(listOf(alice.id, bob.id), vm.state.value.editor?.contactIds)
        vm.saveWidget()
        vm.saveWidget()
        advanceUntilIdle()
        assertEquals(1, repo.hub.value.widgets.size)
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        for (id in listOf("local.favorites", "local.recents")) {
            vm.selectDefinition(id)
            vm.configureSelected()
            advanceUntilIdle()
        }
        val aliceAction = assertNotNull(repo.hub.value.contactTarget(alice.id)).id
        val bobAction = assertNotNull(repo.hub.value.contactTarget(bob.id)).id
        repo.recordSuccessfulPayment(aliceAction, 10)
        repo.recordSuccessfulPayment(aliceAction, 20)
        repo.recordSuccessfulPayment(bobAction, 30)
        advanceUntilIdle()
        assertEquals(alice.id, vm.state.value.widgets.first { it.kind == HubWidgetKind.Favorites }.people.first().contactId)
        assertEquals(bob.id, vm.state.value.widgets.first { it.kind == HubWidgetKind.Recents }.people.first().contactId)
        vm.editWidget(repo.hub.value.widgets.first { it.kind == HubWidgetKind.Contacts }.id)
        vm.deleteContact(alice.id)
        advanceUntilIdle()
        assertEquals(listOf(bob.id), vm.state.value.editor?.contactIds)
        vm.deleteContact(bob.id)
        advanceUntilIdle()
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        vm.clear()
    }

    @Test
    fun contactSelectionReplacesSingleAndEnforcesEachLayoutCapacity() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val contacts = (1..7).map {
            repo.saveContact(assertNotNull(LightningAddress.parse("person$it@example.com")), "Person $it")
        }
        advanceUntilIdle()
        vm.selectDefinition("local.contacts")
        assertEquals(listOf(contacts[0].id), vm.state.value.editor?.contactIds)
        vm.toggleContact(contacts[1].id)
        assertEquals(listOf(contacts[1].id), vm.state.value.editor?.contactIds)
        vm.addContact("New", "new@example.com")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.editor?.contactIds?.size)
        for (variant in listOf(LocalHubWidgets.Row, LocalHubWidgets.Card)) {
            vm.selectDefinition("local.contacts")
            vm.selectVariant(variant.id)
            assertEquals(variant.capacity, vm.state.value.editor?.contactIds?.size)
            vm.toggleContact(contacts[variant.capacity].id)
            assertEquals(variant.capacity, vm.state.value.editor?.contactIds?.size)
            assertEquals(HubWidgetError.TooManyContacts, vm.state.value.error)
            vm.toggleContact(contacts[0].id)
            vm.toggleContact(contacts[variant.capacity].id)
            assertEquals(variant.capacity, vm.state.value.editor?.contactIds?.size)
            assertNull(vm.state.value.editorValidationError())
        }
        vm.clear()
    }

    @Test
    fun shortcutRequiresOneContactAndPositivePresetThenSaves() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val alice = repo.saveContact(assertNotNull(LightningAddress.parse("alice@example.com")), "Alice")
        val bob = repo.saveContact(assertNotNull(LightningAddress.parse("bob@example.com")), "Bob")
        advanceUntilIdle()
        vm.selectDefinition("local.shortcut")
        vm.configureSelected()
        // The top contact is preselected; the amount is what a shortcut still needs.
        assertEquals(HubWidgetScreen.Configure, vm.state.value.screen)
        assertEquals(listOf(alice.id), vm.state.value.editor?.contactIds)
        assertEquals(HubWidgetError.InvalidAmount, vm.state.value.editorValidationError())
        vm.toggleContact(bob.id)
        assertEquals(listOf(bob.id), vm.state.value.editor?.contactIds)
        assertEquals(HubWidgetError.InvalidAmount, vm.state.value.editorValidationError())
        vm.updateAmount("0")
        assertEquals(HubWidgetError.InvalidAmount, vm.state.value.editorValidationError())
        vm.updateAmount("12.50")
        assertNull(vm.state.value.editorValidationError())
        vm.saveWidget()
        advanceUntilIdle()
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        assertEquals(HubWidgetKind.Shortcut, repo.hub.value.widgets.single().kind)
        assertEquals(1250L, vm.state.value.widgets.single().people.single().amount?.minor)
        vm.clear()
    }

    @Test
    fun contactsAreAddedWithMostPaidDefaultsAndResizeInPlace() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        val contacts = (1..5).map {
            repo.saveContact(assertNotNull(LightningAddress.parse("person$it@example.com")), "Person $it")
        }
        advanceUntilIdle()
        // Pay the third contact through a widget, then start from an empty Hub.
        repo.saveWidget(
            HubWidgetDraft("local.contacts", HubWidgetKind.Contacts, LocalHubWidgets.Single, contactIds = listOf(contacts[2].id)),
            null
        )
        repo.hub.value.widgets.forEach { repo.deleteWidget(it.id) }
        val third = assertNotNull(repo.hub.value.contactTarget(contacts[2].id)).id
        repo.recordSuccessfulPayment(third, 10, paidSats = 2_100)
        advanceUntilIdle()

        vm.selectDefinition("local.contacts")
        vm.selectVariant(LocalHubWidgets.Row.id)
        assertEquals(true, vm.state.value.editor?.addsWithDefaults())
        vm.configureSelected()
        advanceUntilIdle()
        assertEquals(HubWidgetScreen.Hub, vm.state.value.screen)
        val widget = repo.hub.value.widgets.single()
        assertEquals(
            listOf(contacts[2], contacts[0], contacts[1], contacts[3]).map { it.id },
            widget.contactIds
        )
        val person = vm.state.value.widgets.single().people.first()
        assertEquals(2_100L, person.lastPaid?.minor)

        vm.resizeWidget(widget.id, LocalHubWidgets.Single.id)
        advanceUntilIdle()
        assertEquals(listOf(contacts[2].id), repo.hub.value.widget(widget.id)?.contactIds)
        vm.resizeWidget(widget.id, LocalHubWidgets.Card.id)
        advanceUntilIdle()
        assertEquals(LocalHubWidgets.Card, repo.hub.value.widget(widget.id)?.variant)
        assertEquals(contacts[2].id, repo.hub.value.widget(widget.id)?.contactIds?.first())
        assertEquals(5, repo.hub.value.widget(widget.id)?.contactIds?.size)
        vm.clear()
    }

    @Test
    fun contactsWidgetAsksForAContactOnlyWhenThereIsNone() = runTest {
        val repo = DefaultPaymentHubRepository(MapSettings())
        val host = PaymentHubController(repo, this)
        val vm = WidgetHubViewModel(repo, host, { "USD" }, dispatcher = StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        vm.selectDefinition("local.contacts")
        vm.configureSelected()
        assertEquals(HubWidgetScreen.Configure, vm.state.value.screen)
        vm.clear()
    }
}
