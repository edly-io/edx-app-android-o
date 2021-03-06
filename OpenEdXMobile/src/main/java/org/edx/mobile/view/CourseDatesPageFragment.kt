package org.edx.mobile.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import org.edx.mobile.R
import org.edx.mobile.core.EdxEnvironment
import org.edx.mobile.databinding.FragmentCourseDatesPageBinding
import org.edx.mobile.exception.ErrorMessage
import org.edx.mobile.http.HttpStatus
import org.edx.mobile.http.HttpStatusException
import org.edx.mobile.http.notifications.FullScreenErrorNotification
import org.edx.mobile.interfaces.OnDateBlockListener
import org.edx.mobile.model.course.CourseBannerInfoModel
import org.edx.mobile.model.course.CourseBannerType
import org.edx.mobile.util.BrowserUtil
import org.edx.mobile.util.UiUtil
import org.edx.mobile.view.adapters.CourseDatesAdapter
import org.edx.mobile.view.dialog.AlertDialogFragment
import org.edx.mobile.viewModel.CourseDateViewModel
import org.edx.mobile.viewModel.ViewModelFactory
import javax.inject.Inject

class CourseDatesPageFragment : OfflineSupportBaseFragment() {

    @Inject
    private lateinit var environment: EdxEnvironment
    private lateinit var errorNotification: FullScreenErrorNotification

    private lateinit var binding: FragmentCourseDatesPageBinding
    private lateinit var viewModel: CourseDateViewModel
    private var onDateItemClick: OnDateBlockListener = object : OnDateBlockListener {
        override fun onClick(link: String) {
            BrowserUtil.open(activity, link)
        }
    }

    companion object {
        @JvmStatic
        fun makeArguments(courseId: String): Bundle {
            val courseBundle = Bundle()
            courseBundle.putString(Router.EXTRA_COURSE_ID, courseId)
            return courseBundle
        }
    }

    override fun isShowingFullScreenError(): Boolean {
        return errorNotification.isShowing
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_course_dates_page, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(this, ViewModelFactory()).get(CourseDateViewModel::class.java)

        errorNotification = FullScreenErrorNotification(binding.swipeContainer)

        binding.swipeContainer.setOnRefreshListener {
            // Hide the progress bar as swipe layout has its own progress indicator
            binding.loadingIndicator.loadingIndicator.visibility = View.GONE
            errorNotification.hideError()
            viewModel.fetchCourseDates(courseID = getStringArgument(Router.EXTRA_COURSE_ID), isSwipeRefresh = true)
        }
        UiUtil.setSwipeRefreshLayoutColors(binding.swipeContainer)
        initObserver()
        viewModel.fetchCourseDates(courseID = getStringArgument(Router.EXTRA_COURSE_ID), isSwipeRefresh = false)
    }

    private fun initObserver() {
        viewModel.showLoader.observe(this, Observer { showLoader ->
            binding.loadingIndicator.loadingIndicator.visibility = if (showLoader) View.VISIBLE else View.GONE
        })

        viewModel.bannerInfo.observe(this, Observer {
            initDatesBanner(it)
        })

        viewModel.courseDates.observe(this, Observer { dates ->
            if (dates.courseDateBlocks.isNullOrEmpty()) {
                viewModel.setError(ErrorMessage.COURSE_DATES_CODE, HttpStatus.NO_CONTENT, getString(R.string.course_dates_unavailable_message))
            } else {
                dates.organiseCourseDates()
                binding.rvDates.apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = CourseDatesAdapter(dates.courseDatesMap, onDateItemClick)
                }
            }
        })

        viewModel.resetCourseDates.observe(this, Observer { resetCourseDates ->
            if (resetCourseDates != null) {
                AlertDialogFragment.newInstance(getString(R.string.course_dates_reset_title),
                        getString(R.string.course_dates_reset_successful),
                        null)
                        .show(childFragmentManager, null)
            }
        })

        viewModel.errorMessage.observe(this, Observer { errorMsg ->
            if (errorMsg != null) {
                if (errorMsg.throwable is HttpStatusException) {
                    when (errorMsg.throwable.statusCode) {
                        HttpStatus.UNAUTHORIZED -> {
                            environment.router?.forceLogout(contextOrThrow,
                                    environment.analyticsRegistry,
                                    environment.notificationDelegate)
                            return@Observer
                        }
                        else ->
                            errorNotification.showError(contextOrThrow, errorMsg.throwable, -1, null)
                    }
                } else {
                    when (errorMsg.errorCode) {
                        ErrorMessage.COURSE_DATES_CODE ->
                            errorNotification.showError(contextOrThrow, errorMsg.throwable, -1, null)
                        ErrorMessage.BANNER_INFO_CODE ->
                            initDatesBanner(null)
                        ErrorMessage.COURSE_RESET_DATES_CODE ->
                            AlertDialogFragment.newInstance(getString(R.string.course_dates_reset_title),
                                    getString(R.string.course_dates_reset_unsuccessful), null)
                    }
                }
            }
        })

        viewModel.swipeRefresh.observe(this, Observer { enableSwipeListener ->
            binding.swipeContainer.isRefreshing = enableSwipeListener
        })
    }

    /**
     * Initialized dates info banner on CourseDatesPageFragment
     *
     * @param courseBannerInfo object of course deadline info
     */
    private fun initDatesBanner(courseBannerInfo: CourseBannerInfoModel?) {
        var buttonText: String? = ""
        when (courseBannerInfo?.datesBannerInfo?.getCourseBannerType()) {
            CourseBannerType.UPGRADE_TO_GRADED -> binding.banner.bannerInfo.text = getText(R.string.course_dates_banner_upgrade_to_graded)
            CourseBannerType.UPGRADE_TO_RESET -> binding.banner.bannerInfo.text = getText(R.string.course_dates_banner_upgrade_to_reset)
            CourseBannerType.RESET_DATES -> {
                binding.banner.bannerInfo.text = getText(R.string.course_dates_banner_reset_date)
                buttonText = contextOrThrow.getString(R.string.course_dates_banner_reset_date_button)
            }
            CourseBannerType.INFO_BANNER -> binding.banner.bannerInfo.text = getText(R.string.course_dates_info_banner)
            CourseBannerType.BLANK -> {
                binding.banner.containerLayout.visibility = View.GONE
            }
        }
        if (binding.banner.bannerInfo.text.isNullOrBlank().not()) {
            if (buttonText.isNullOrBlank().not()) {
                binding.banner.btnShiftDates.text = buttonText
                binding.banner.btnShiftDates.visibility = View.VISIBLE
                binding.banner.btnShiftDates.setOnClickListener {
                    viewModel.resetCourseDatesBanner(getStringArgument(Router.EXTRA_COURSE_ID))
                }
            }
            binding.banner.containerLayout.visibility = View.VISIBLE
        }
    }
}
